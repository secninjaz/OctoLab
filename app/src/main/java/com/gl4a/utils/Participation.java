package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabGraphQLService;
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
 *
 * With {@code updatedAfter} (the background check), GraphQL first finds which of them changed
 * since then, for issues and merge requests together: one request for the created, assigned and
 * reviewing ones and one per {@link #PROJECTS_PER_QUERY} projects the user commented in. Only
 * those are loaded, so a check costs a few requests however many there are (#275).
 */
public final class Participation {
    private static final String PREFS = "participation";
    /** On a first read, at most this many pages of 100 of the user's comments. */
    private static final int MAX_EVENT_PAGES = 10;
    /** Items of each list ("created", "assigned", ...) taken, latest activity first. */
    private static final int LIST_SIZE = 100;
    /** iids per request when loading commented-on items of a project. */
    private static final int IIDS_PER_REQUEST = 100;
    /** Projects asked at once for their changed issues and merge requests (#275). */
    private static final int PROJECTS_PER_QUERY = 50;
    /** Changed items asked per project; a project with more is loaded as before. */
    private static final int CHANGED_PER_PROJECT = 20;

    private final Context mContext;
    private final String mLogin;
    private final GitLabUserService mUsers;
    private final GitLabIssueService mIssues;
    private final GitLabMergeRequestService mMergeRequests;
    private final GitLabGraphQLService mGraphQL;
    /** The commented-on refs, once read: the feed is read once per instance unless rebuilt. */
    @Nullable private Set<String> mCommented;
    /** The changed refs since {@link #mChangedAfter}, shared by issues and merge requests. */
    @Nullable private Set<String> mChanged;
    @Nullable private String mChangedAfter;

    /**
     * GitLab couldn't be reached during the background check (no connection, a timeout, a
     * server error): the check stops and is tried again next time, so nothing is missed (#275).
     */
    public static final class UnreachableException extends RuntimeException {
        public UnreachableException(Throwable cause) {
            super(cause);
        }

        UnreachableException(String message) {
            super(message);
        }
    }

    /** Whether a request failed because GitLab couldn't be reached, not because of its answer. */
    public static boolean isUnreachable(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof UnreachableException) {
                return true;
            }
        }
        return false;
    }

    /** In the background check, an error answer of the server's own (5xx, 429) stops it. */
    private static void checkAnswered(Response<?> response, boolean strict) {
        if (strict && (response.code() >= 500 || response.code() == 429)) {
            throw new UnreachableException("HTTP " + response.code());
        }
    }

    private Participation(Context context, String login, GitLabUserService users,
            GitLabIssueService issues, GitLabMergeRequestService mergeRequests,
            GitLabGraphQLService graphQL) {
        mContext = context.getApplicationContext();
        mLogin = login;
        mUsers = users;
        mIssues = issues;
        mMergeRequests = mergeRequests;
        mGraphQL = graphQL;
    }

    /**
     * For the active account, e.g. its Participating tabs: bound to it for the whole load, its
     * server, token and login taken now (#282). The active account's own services pick the
     * account when each request is sent, so a load still running when the account changed sent
     * its later requests, for the old account's items, to the new account's server.
     */
    public static Participation forActiveAccount(Context context, boolean bypassCache) {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        return forAccount(context, app.getAuthLogin(), app.getApiBaseUrl(), app.getAuthToken());
    }

    /** For any account, e.g. its notifications in the background check. */
    public static Participation forAccount(Context context, String login, String apiBase,
            String token) {
        return new Participation(context, login,
                ServiceFactory.getForAccount(GitLabUserService.class, apiBase, token),
                ServiceFactory.getForAccount(GitLabIssueService.class, apiBase, token),
                ServiceFactory.getForAccount(GitLabMergeRequestService.class, apiBase, token),
                // GraphQL lives at …/api/graphql, beside …/api/v4/
                ServiceFactory.getForAccount(GitLabGraphQLService.class,
                        apiBase.replaceFirst("v4/?$", ""), token));
    }

    /**
     * The issues or merge requests the user participates in, latest activity first.
     *
     * @param updatedAfter only those updated after this ISO 8601 time; null for all. This is
     *                     the background check: if GitLab can't be reached it throws
     *                     {@link UnreachableException} instead of answering with what it has
     * @param rebuild      read the activity feed again instead of only its newer events
     */
    public List<GitLabIssue> items(boolean mergeRequests, @Nullable String updatedAfter,
            boolean rebuild) {
        Map<String, GitLabIssue> byId = new LinkedHashMap<>();
        Set<String> changed = updatedAfter != null ? changedRefs(updatedAfter, rebuild) : null;
        if (changed != null) {
            // The background check: only the items that changed, found with GraphQL (#275)
            loadByIids(byId, mergeRequests, byProject(changed, mergeRequests), true);
        } else {
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
                    checkAnswered(response, updatedAfter != null);
                    add(byId, response);
                } catch (UnreachableException e) {
                    throw e;
                } catch (Exception e) {
                    if (updatedAfter != null && isUnreachable(e)) {
                        throw new UnreachableException(e);
                    }
                    // One list failing leaves the others
                }
            }
            // Commented on: by project, one request per project (per 100 items)
            loadByIids(byId, mergeRequests, byProject(commentedRefs(rebuild), mergeRequests),
                    updatedAfter != null);
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

    private static final String KEY_LISTS_VERSION = "lists_version";
    /** Lists saved before this version may mix accounts' servers (#288). */
    private static final int LISTS_VERSION = 2;

    /**
     * Before #282, switching account could save one account's commented-on items under
     * another's name, so its lookups went to the wrong server (and could match an unrelated
     * project there). Once: every account's list is dropped, and rebuilt from its own server
     * on its next use (#288).
     */
    private static synchronized void resetMixedLists(SharedPreferences prefs) {
        if (prefs.getInt(KEY_LISTS_VERSION, 0) >= LISTS_VERSION) return;
        prefs.edit().clear().putInt(KEY_LISTS_VERSION, LISTS_VERSION).commit();
    }

    /** The issue ("issue:…") or merge request ("mr:…") refs' numbers, by project. */
    private static Map<Long, List<Integer>> byProject(Set<String> refs, boolean mergeRequests) {
        Map<Long, List<Integer>> iidsByProject = new LinkedHashMap<>();
        for (String ref : refs) {
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
        return iidsByProject;
    }

    /**
     * Loads items by project and number, one request per project (per 100 items).
     *
     * @param strict the background check: GitLab not reachable throws, see {@link #items}
     */
    private void loadByIids(Map<String, GitLabIssue> byId, boolean mergeRequests,
            Map<Long, List<Integer>> iidsByProject, boolean strict) {
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
                    checkAnswered(response, strict);
                    add(byId, response);
                } catch (UnreachableException e) {
                    throw e;
                } catch (Exception e) {
                    if (strict && isUnreachable(e)) {
                        throw new UnreachableException(e);
                    }
                    // A project no longer visible
                }
            }
        }
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
        if (!rebuild && mCommented != null) {
            return mCommented;
        }
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        resetMixedLists(prefs);
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
        mCommented = refs;
        return refs;
    }

    /**
     * The issues and merge requests the user participates in that changed after
     * {@code updatedAfter}, as refs, found with GraphQL (#275): one request for the created,
     * assigned and reviewing ones, and one per {@link #PROJECTS_PER_QUERY} projects the user
     * commented in, for both kinds at once. Null if GitLab answers that it can't (e.g. an older
     * version without these fields), to load them as before; if it can't be reached, throws
     * {@link UnreachableException}.
     * Kept for the instance, so issues and merge requests share the requests.
     */
    @Nullable
    private synchronized Set<String> changedRefs(String updatedAfter, boolean rebuild) {
        if (updatedAfter.equals(mChangedAfter)) {
            return mChanged;
        }
        // Those the user unsubscribed from on GitLab are left out: no notifications for them,
        // as GitLab sends none (#290). GitLab says so in the same requests.
        Set<String> unsubscribed = new HashSet<>();
        Set<String> changed = listedChanged(updatedAfter, unsubscribed);
        if (changed == null) {
            mChangedAfter = updatedAfter;
            mChanged = null;
            return null;
        }

        // Commented on: each project's changed items, for both kinds, kept if commented on
        Set<String> commented = commentedRefs(rebuild);
        Map<Long, List<String>> refsByProject = new LinkedHashMap<>();
        for (String ref : commented) {
            String[] parts = ref.split(":");
            if (parts.length != 3) continue;
            try {
                long projectId = Long.parseLong(parts[1]);
                if (!refsByProject.containsKey(projectId)) {
                    refsByProject.put(projectId, new ArrayList<>());
                }
                refsByProject.get(projectId).add(ref);
            } catch (NumberFormatException ignored) {
            }
        }
        String query = "query($ids: [ID!], $t: Time) { projects(ids: $ids, first: "
                + PROJECTS_PER_QUERY + ") { nodes { id"
                + " issues(updatedAfter: $t, first: " + CHANGED_PER_PROJECT + ")"
                + " { pageInfo { hasNextPage } nodes { iid subscribed } }"
                + " mergeRequests(updatedAfter: $t, first: " + CHANGED_PER_PROJECT + ")"
                + " { pageInfo { hasNextPage } nodes { iid subscribed } } } } }";
        List<Long> projectIds = new ArrayList<>(refsByProject.keySet());
        for (int i = 0; i < projectIds.size(); i += PROJECTS_PER_QUERY) {
            List<Long> batch = projectIds.subList(i,
                    Math.min(projectIds.size(), i + PROJECTS_PER_QUERY));
            List<String> ids = new ArrayList<>();
            for (long id : batch) {
                ids.add("gid://gitlab/Project/" + id);
            }
            Map<String, Object> variables = new HashMap<>();
            variables.put("ids", ids);
            variables.put("t", updatedAfter);
            Object nodes = get(get(get(graphQL(query, variables), "data"), "projects"), "nodes");
            if (!(nodes instanceof List)) {
                // GitLab can't answer this: these projects' items are loaded as before
                for (long id : batch) {
                    changed.addAll(refsByProject.get(id));
                }
                continue;
            }
            for (Object node : (List<?>) nodes) {
                long projectId = gidNumber(get(node, "id"));
                List<String> refs = refsByProject.get(projectId);
                if (refs == null) continue;
                for (String kind : new String[] { "issue", "mr" }) {
                    Object items = get(node, "issue".equals(kind) ? "issues" : "mergeRequests");
                    if (Boolean.TRUE.equals(get(get(items, "pageInfo"), "hasNextPage"))) {
                        // More changed than asked for: all of this kind, as before
                        for (String ref : refs) {
                            if (ref.startsWith(kind + ":")) changed.add(ref);
                        }
                        continue;
                    }
                    Object itemNodes = get(items, "nodes");
                    if (!(itemNodes instanceof List)) continue;
                    for (Object item : (List<?>) itemNodes) {
                        String ref = kind + ":" + projectId + ":" + get(item, "iid");
                        if (Boolean.FALSE.equals(get(item, "subscribed"))) unsubscribed.add(ref);
                        if (commented.contains(ref)) changed.add(ref);
                    }
                }
            }
        }
        changed.removeAll(unsubscribed);
        mChangedAfter = updatedAfter;
        mChanged = changed;
        return changed;
    }

    /**
     * The created, assigned and reviewing items changed after {@code updatedAfter}, as refs, in
     * one GraphQL request; null if it can't answer or there are more than it asks for.
     */
    @Nullable
    private Set<String> listedChanged(String updatedAfter, Set<String> unsubscribed) {
        if (mLogin == null) return null;
        String page = "(updatedAfter: $t, first: " + LIST_SIZE + ")"
                + " { pageInfo { hasNextPage } nodes { iid projectId subscribed } }";
        String query = "query($u: String!, $us: [String!], $t: Time) {"
                + " created: issues(authorUsername: $u, updatedAfter: $t, first: " + LIST_SIZE
                + ") { pageInfo { hasNextPage } nodes { iid projectId subscribed } }"
                + " assigned: issues(assigneeUsernames: $us, updatedAfter: $t, first: " + LIST_SIZE
                + ") { pageInfo { hasNextPage } nodes { iid projectId subscribed } }"
                + " currentUser { authoredMergeRequests" + page + " assignedMergeRequests" + page
                + " reviewRequestedMergeRequests" + page + " } }";
        Map<String, Object> variables = new HashMap<>();
        variables.put("u", mLogin);
        variables.put("us", Collections.singletonList(mLogin));
        variables.put("t", updatedAfter);
        Object data = get(graphQL(query, variables), "data");
        Object user = get(data, "currentUser");
        Object[][] lists = {
                { "issue", get(data, "created") },
                { "issue", get(data, "assigned") },
                { "mr", get(user, "authoredMergeRequests") },
                { "mr", get(user, "assignedMergeRequests") },
                { "mr", get(user, "reviewRequestedMergeRequests") } };
        Set<String> refs = new HashSet<>();
        for (Object[] list : lists) {
            Object nodes = get(list[1], "nodes");
            if (!(nodes instanceof List)
                    || Boolean.TRUE.equals(get(get(list[1], "pageInfo"), "hasNextPage"))) {
                return null;
            }
            for (Object item : (List<?>) nodes) {
                Object projectId = get(item, "projectId");
                if (projectId instanceof Number) {
                    String ref = list[0] + ":" + ((Number) projectId).longValue() + ":"
                            + get(item, "iid");
                    refs.add(ref);
                    if (Boolean.FALSE.equals(get(item, "subscribed"))) unsubscribed.add(ref);
                }
            }
        }
        return refs;
    }

    /**
     * A GraphQL request's answer; null if GitLab answered that it can't (4xx). Throws
     * {@link UnreachableException} if GitLab couldn't be reached or failed itself (5xx, 429).
     */
    @Nullable
    private Map<String, Object> graphQL(String query, Map<String, Object> variables) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        Response<Map<String, Object>> response;
        try {
            response = mGraphQL.rawQuery(body).blockingGet();
        } catch (Exception e) {
            if (isUnreachable(e)) throw new UnreachableException(e);
            return null;
        }
        checkAnswered(response, true);
        return response.isSuccessful() ? response.body() : null;
    }

    /** The number at the end of a GraphQL ID ("gid://gitlab/Project/12"); 0 if none. */
    private static long gidNumber(@Nullable Object gid) {
        String id = String.valueOf(gid);
        try {
            return Long.parseLong(id.substring(id.lastIndexOf('/') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static Object get(@Nullable Object map, String key) {
        return map instanceof Map ? ((Map<?, ?>) map).get(key) : null;
    }
}
