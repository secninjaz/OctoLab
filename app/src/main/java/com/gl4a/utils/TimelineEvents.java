package com.gl4a.utils;

import android.util.Log;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabMergeRequestRefsResponse;
import com.gl4a.gitlab.model.GitLabResourceEvent;
import com.gl4a.gitlab.service.GitLabGraphQLService;
import com.gl4a.gitlab.service.GitLabIssueService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Single;

/**
 * Label, milestone and state changes of an issue or MR as timeline system notes (#180).
 * GitLab keeps these in resource events rather than notes, so the discussions API alone
 * misses them; GitLab web merges them into the timeline. Label adds/removes made together
 * (same user, same second) become one note, as on GitLab web.
 */
public final class TimelineEvents {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private TimelineEvents() {}

    /** Emits an empty list if the events can't be loaded, so the timeline still shows. */
    public static Single<List<GitLabComment>> load(long projectId, boolean isMergeRequest,
            int iid, boolean bypassCache) {
        return Single.fromCallable(() -> loadAll(projectId, isMergeRequest, iid, bypassCache))
                .onErrorReturn(error -> {
                    Log.d(Gl4Application.LOG_TAG, "Resource events unavailable", error);
                    return Collections.<GitLabComment>emptyList();
                });
    }

    private static List<GitLabComment> loadAll(long projectId, boolean isMergeRequest, int iid,
            boolean bypassCache) {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, bypassCache);
        String type = isMergeRequest ? "merge_requests" : "issues";
        List<GitLabComment> notes = new ArrayList<>();

        // Labels: group by user + second, like GitLab's "added X Y labels and removed Z label".
        Map<String, GitLabComment> labelGroups = new LinkedHashMap<>();
        for (GitLabResourceEvent e : fetch(service, projectId, type, iid, "label")) {
            if (e.label == null) continue;
            String key = (e.user != null ? e.user.id : 0) + "@" + second(e.createdAt);
            GitLabComment note = labelGroups.get(key);
            if (note == null) {
                note = makeNote(e, new GitLabComment.EventInfo("label"));
                labelGroups.put(key, note);
            }
            ("remove".equals(e.action) ? note.eventInfo.removed : note.eventInfo.added)
                    .add(e.label);
        }
        notes.addAll(labelGroups.values());

        for (GitLabResourceEvent e : fetch(service, projectId, type, iid, "milestone")) {
            GitLabComment.EventInfo info = new GitLabComment.EventInfo("milestone");
            info.milestone = e.milestone != null ? e.milestone.title : null;
            info.milestoneRemoved = "remove".equals(e.action);
            notes.add(makeNote(e, info));
        }
        Map<Long, List<GitLabComment.EventInfo>> byMergeRequest = new LinkedHashMap<>();
        for (GitLabResourceEvent e : fetch(service, projectId, type, iid, "state")) {
            GitLabComment.EventInfo info = new GitLabComment.EventInfo("state");
            info.state = e.state;
            info.sourceCommit = e.sourceCommit;
            if (e.sourceMergeRequestId != null) {
                List<GitLabComment.EventInfo> infos = byMergeRequest.get(e.sourceMergeRequestId);
                if (infos == null) {
                    infos = new ArrayList<>();
                    byMergeRequest.put(e.sourceMergeRequestId, infos);
                }
                infos.add(info);
            }
            notes.add(makeNote(e, info));
        }
        addMergeRequests(byMergeRequest);
        return notes;
    }

    /**
     * Fills in the MRs that closed the issue ("closed with merge request fmd-server!44"),
     * #200. The events only give the MR's global ID, so they're looked up over GraphQL, all
     * in one query; best effort, the events still show as "closed" without them.
     */
    private static void addMergeRequests(Map<Long, List<GitLabComment.EventInfo>> byId) {
        if (byId.isEmpty()) return;
        List<Long> ids = new ArrayList<>(byId.keySet());
        StringBuilder query = new StringBuilder("query {");
        for (int i = 0; i < ids.size(); i++) {
            query.append(" m").append(i).append(": mergeRequest(id: \"gid://gitlab/MergeRequest/")
                    .append(ids.get(i)).append("\") { iid state webUrl project { fullPath } }");
        }
        query.append(" }");
        Map<String, Object> body = new HashMap<>();
        body.put("query", query.toString());
        try {
            GitLabMergeRequestRefsResponse response = ServiceFactory
                    .getGraphQL(GitLabGraphQLService.class).getMergeRequestsById(body)
                    .blockingGet().body();
            if (response == null || response.data == null) return;
            for (int i = 0; i < ids.size(); i++) {
                GitLabMergeRequestRefsResponse.MergeRequest mr = response.data.get("m" + i);
                if (mr == null || mr.iid == null || mr.project == null) continue;
                for (GitLabComment.EventInfo info : byId.get(ids.get(i))) {
                    info.sourceMrProject = mr.project.fullPath;
                    info.sourceMrIid = mr.iid;
                    info.sourceMrState = mr.state;
                    info.sourceMrUrl = mr.webUrl;
                }
            }
        } catch (RuntimeException e) {
            Log.d(Gl4Application.LOG_TAG, "Closing merge requests unavailable", e);
        }
    }

    private static List<GitLabResourceEvent> fetch(GitLabIssueService service, long projectId,
            String type, int iid, String kind) {
        List<GitLabResourceEvent> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<GitLabResourceEvent> batch = ApiHelpers.throwOnFailure(service
                    .getResourceEvents(projectId, type, iid, kind, page, PAGE_SIZE).blockingGet());
            if (batch == null || batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        return all;
    }

    private static GitLabComment makeNote(GitLabResourceEvent e, GitLabComment.EventInfo info) {
        GitLabComment note = new GitLabComment();
        // Negative, per-kind ids can't collide with real note ids (used as cache keys).
        note.id = -(e.id * 4 + ("label".equals(info.kind) ? 1 : "milestone".equals(info.kind) ? 2 : 3));
        note.author = e.user;
        note.createdAt = e.createdAt;
        note.system = true;
        note.body = "";
        note.eventInfo = info;
        return note;
    }

    /** "2026-09-29T03:00:57.660Z" → "2026-09-29T03:00:57" */
    private static String second(String isoTime) {
        return isoTime != null && isoTime.length() >= 19 ? isoTime.substring(0, 19) : isoTime;
    }
}
