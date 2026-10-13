package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.gitlab.service.GitLabUserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit2.Response;

/**
 * THE definition of "participating" (#267), for the issues' and merge requests' Participating
 * tabs and for notifications about new comments. GitLab has no participating list or API
 * (its participating notifications are emails only), so it's built from GitLab's own data:
 * issues and MRs the user created, is assigned to, reviews (MRs) or commented on.
 *
 * "Commented on" comes from the user's activity feed (GET /events?action=commented). Which
 * items those are (project and number, nothing else) is remembered per account: the feed is
 * read once, then only events newer than the last one seen; {@code rebuild} reads it again.
 * The items themselves are always fetched fresh. All methods block: call off the main thread.
 */
public final class Participation {
    private static final String PREFS = "participation";
    /** On a first read, at most this many pages of 100 of the user's comments. */
    private static final int MAX_EVENT_PAGES = 10;
    /** Items of each list ("created", "assigned", ...) taken, latest activity first. */
    private static final int LIST_SIZE = 100;
    /** iids per request when loading commented-on items of a project. */
    private static final int IIDS_PER_REQUEST = 100;

    private final Context mContext;
    private final String mLogin;
    private final GitLabUserService mUsers;
    private final GitLabIssueService mIssues;
    private final GitLabMergeRequestService mMergeRequests;

    private Participation(Context context, String login, GitLabUserService users,
            GitLabIssueService issues, GitLabMergeRequestService mergeRequests) {
        mContext = context.getApplicationContext();
        mLogin = login;
        mUsers = users;
        mIssues = issues;
        mMergeRequests = mergeRequests;
    }

    /** For the active account, e.g. its Participating tabs. */
    public static Participation forActiveAccount(Context context, boolean bypassCache) {
        return new Participation(context, com.gl4a.Gl4Application.get().getAuthLogin(),
                ServiceFactory.get(GitLabUserService.class, bypassCache),
                ServiceFactory.get(GitLabIssueService.class, bypassCache),
                ServiceFactory.get(GitLabMergeRequestService.class, bypassCache));
    }

    /** For any account, e.g. its notifications in the background check. */
    public static Participation forAccount(Context context, String login, String apiBase,
            String token) {
        return new Participation(context, login,
                ServiceFactory.getForAccount(GitLabUserService.class, apiBase, token),
                ServiceFactory.getForAccount(GitLabIssueService.class, apiBase, token),
                ServiceFactory.getForAccount(GitLabMergeRequestService.class, apiBase, token));
    }

    /**
     * The issues or merge requests the user participates in, latest activity first.
     *
     * @param updatedAfter only those updated after this ISO 8601 time; null for all
     * @param rebuild      read the activity feed again instead of only its newer events
     */
    public List<GitLabIssue> items(boolean mergeRequests, @Nullable String updatedAfter,
            boolean rebuild) {
        Map<String, GitLabIssue> byId = new LinkedHashMap<>();

        // Created, assigned, reviewing: GitLab's own filters
        for (Map<String, String> filter : listFilters(mergeRequests)) {
            filter.put("state", "all");
            filter.put("order_by", "updated_at");
            filter.put("sort", "desc");
            filter.put("with_labels_details", "true");
            if (updatedAfter != null) filter.put("updated_after", updatedAfter);
            try {
                Response<List<GitLabIssue>> response = mergeRequests
                        ? mMergeRequests.listMergeRequestsAsIssues(filter, 1, LIST_SIZE)
                                .blockingGet()
                        : mIssues.listIssuesWithParams(filter, 1, LIST_SIZE).blockingGet();
                add(byId, response);
            } catch (Exception ignored) {
                // One list failing leaves the others
            }
        }

        // Commented on: by project, one request per project (per 100 items)
        Map<Long, List<Integer>> iidsByProject = new LinkedHashMap<>();
        for (String ref : commentedRefs(rebuild)) {
            String[] parts = ref.split(":");
            if (parts.length != 3 || ("mr".equals(parts[0])) != mergeRequests) continue;
            try {
                long projectId = Long.parseLong(parts[1]);
                int iid = Integer.parseInt(parts[2]);
                if (!iidsByProject.containsKey(projectId)) {
                    iidsByProject.put(projectId, new ArrayList<>());
                }
                iidsByProject.get(projectId).add(iid);
            } catch (NumberFormatException ignored) {
            }
        }
        for (Map.Entry<Long, List<Integer>> entry : iidsByProject.entrySet()) {
            List<Integer> iids = entry.getValue();
            for (int i = 0; i < iids.size(); i += IIDS_PER_REQUEST) {
                List<Integer> batch = iids.subList(i, Math.min(iids.size(), i + IIDS_PER_REQUEST));
                try {
                    Response<List<GitLabIssue>> response = mergeRequests
                            ? mMergeRequests.getMergeRequestsByIidsAsIssues(entry.getKey(),
                                    batch, true, IIDS_PER_REQUEST).blockingGet()
                            : mIssues.getIssuesByIids(entry.getKey(), batch, true,
                                    IIDS_PER_REQUEST).blockingGet();
                    add(byId, response);
                } catch (Exception ignored) {
                    // A project no longer visible
                }
            }
        }

        long after = updatedAfter != null ? isoMillis(updatedAfter) : Long.MIN_VALUE;
        List<GitLabIssue> items = new ArrayList<>();
        for (GitLabIssue item : byId.values()) {
            if (updatedAfter == null || isoMillis(item.updatedAt) > after) {
                items.add(item);
            }
        }
        Collections.sort(items, (a, b) -> Long.compare(isoMillis(b.updatedAt),
                isoMillis(a.updatedAt)));
        return items;
    }

    /** An ISO 8601 time as GitLab gives it ("…Z" or "…+05:30"), in epoch ms; 0 if unreadable. */
    public static long isoMillis(@Nullable String iso) {
        if (iso == null) return 0;
        // "…Z" first; offsets ("XXX") need API 24, so on older devices those read as 0
        for (String pattern : new String[] {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX" }) {
            try {
                java.text.SimpleDateFormat format =
                        new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = format.parse(iso);
                if (date != null) return date.getTime();
            } catch (Exception ignored) {
                // Not this format (or not supported on this Android version)
            }
        }
        return 0;
    }

    /** Epoch ms as the ISO 8601 time GitLab's updated_after takes. */
    public static String isoTime(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(millis));
    }

    private List<Map<String, String>> listFilters(boolean mergeRequests) {
        List<Map<String, String>> filters = new ArrayList<>();
        filters.add(new HashMap<>(Collections.singletonMap("scope", "created_by_me")));
        filters.add(new HashMap<>(Collections.singletonMap("scope", "assigned_to_me")));
        if (mergeRequests && mLogin != null) {
            Map<String, String> reviewing = new HashMap<>();
            reviewing.put("scope", "all");
            reviewing.put("reviewer_username", mLogin);
            filters.add(reviewing);
        }
        return filters;
    }

    private static void add(Map<String, GitLabIssue> byId, Response<List<GitLabIssue>> response) {
        if (!response.isSuccessful() || response.body() == null) return;
        for (GitLabIssue item : response.body()) {
            byId.put(String.valueOf(item.id), item);
        }
    }

    /**
     * The items the user commented on, as "issue:projectId:iid" / "mr:projectId:iid",
     * updated from the activity feed: all of it (up to MAX_EVENT_PAGES) the first time or on
     * {@code rebuild}, else only events newer than the last one seen.
     */
    private synchronized Set<String> commentedRefs(boolean rebuild) {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String refsKey = mLogin + ":commented";
        String lastKey = mLogin + ":last_event";
        boolean firstRead = rebuild || !prefs.contains(lastKey);
        Set<String> refs = firstRead ? new HashSet<>()
                : new HashSet<>(prefs.getStringSet(refsKey, Collections.emptySet()));
        long lastSeen = firstRead ? 0 : prefs.getLong(lastKey, 0);
        long newest = lastSeen;
        pages:
        for (int page = 1; page <= MAX_EVENT_PAGES; page++) {
            Response<List<GitLabEvent>> response;
            try {
                response = mUsers.getCurrentUserEventsByAction("commented", page, 100)
                        .blockingGet();
            } catch (Exception e) {
                break;
            }
            if (!response.isSuccessful() || response.body() == null) break;
            List<GitLabEvent> events = response.body();
            for (GitLabEvent event : events) {
                if (event.id <= lastSeen) {
                    break pages;
                }
                newest = Math.max(newest, event.id);
                if (event.note == null || event.note.noteableIid <= 0) continue;
                if ("Issue".equals(event.note.noteableType)) {
                    refs.add("issue:" + event.projectId + ":" + event.note.noteableIid);
                } else if ("MergeRequest".equals(event.note.noteableType)) {
                    refs.add("mr:" + event.projectId + ":" + event.note.noteableIid);
                }
            }
            if (events.size() < 100) {
                break;
            }
        }
        if (firstRead || newest > lastSeen) {
            prefs.edit().putStringSet(refsKey, refs).putLong(lastKey, newest).apply();
        }
        return refs;
    }
}
