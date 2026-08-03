package com.gl4a.utils;

import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabTodoService;
import com.gl4a.model.NotificationHolder;
import com.gl4a.model.NotificationListLoadResult;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Single;

public class SingleFactory {

    /**
     * In-process cache: "owner/repo" → numeric project ID.
     * Populated on first resolution so subsequent per-page calls skip the network lookup.
     */
    private static final ConcurrentHashMap<String, Long> sProjectIdCache = new ConcurrentHashMap<>();

    /** Clears the project-ID cache, e.g. after logout or instance URL change. */
    public static void clearProjectIdCache() {
        sProjectIdCache.clear();
    }

    /** URL-encodes "namespace/repo" for use with the GitLab projects/:id path. */
    private static String encodedPath(String repoOwner, String repoName) {
        try {
            return URLEncoder.encode(repoOwner + "/" + repoName, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always supported
            return repoOwner + "%2F" + repoName;
        }
    }

    // ---------------------------------------------------------------------------
    // Collaborator check
    // ---------------------------------------------------------------------------

    /**
     * Returns a Single that emits {@code true} if the currently logged-in user
     * is a member (developer or above) of the given project.
     */
    public static Single<Boolean> isAppUserRepoCollaborator(String repoOwner, String repoName,
            boolean bypassCache) {
        Gl4Application app = Gl4Application.get();
        if (!app.isAuthorized()) {
            return Single.just(false);
        }

        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, bypassCache);

        // Resolve owner/repo → project, then check permissions
        return service.getProjectByPath(encodedPath(repoOwner, repoName))
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) return false;
                    GitLabProject project = response.body();
                    if (project.permissions == null) return false;
                    // Developer (30) or above can push → treated as collaborator
                    return project.permissions.canPush();
                })
                .onErrorReturn(e -> false);
    }

    // ---------------------------------------------------------------------------
    // Project ID helpers
    // ---------------------------------------------------------------------------

    /**
     * Resolves a numeric GitLab project ID from owner namespace and project path.
     * The result is cached in-process so repeated calls (e.g. per-page loads in
     * StargazerListFragment, ForkListFragment, etc.) skip the redundant network lookup.
     */
    public static Single<Long> getProjectId(String repoOwner, String repoName) {
        String cacheKey = repoOwner + "/" + repoName;
        Long cached = sProjectIdCache.get(cacheKey);
        if (cached != null) {
            return Single.just(cached);
        }
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, false);
        return service.getProjectByPath(encodedPath(repoOwner, repoName))
                .map(ApiHelpers::throwOnFailure)
                .map(project -> {
                    sProjectIdCache.put(cacheKey, project.id);
                    return project.id;
                });
    }

    // ---------------------------------------------------------------------------
    // Merge Request helpers
    // ---------------------------------------------------------------------------

    /**
     * Fetches a single merge request by owner/repo path and internal issue number (iid).
     * Label colours are enriched by a follow-up call to GET /projects/{id}/labels because
     * the single MR endpoint silently ignores the with_labels_details parameter.
     */
    public static Single<GitLabMergeRequest> getMergeRequest(String repoOwner, String repoName,
            int iid, boolean bypassCache) {
        GitLabProjectService projectService =
                ServiceFactory.get(GitLabProjectService.class, bypassCache);
        GitLabMergeRequestService mrService =
                ServiceFactory.get(GitLabMergeRequestService.class, bypassCache);
        GitLabIssueService issueService =
                ServiceFactory.get(GitLabIssueService.class, bypassCache);

        return projectService.getProjectByPath(encodedPath(repoOwner, repoName))
                .map(ApiHelpers::throwOnFailure)
                .flatMap(project -> mrService.getMergeRequest(project.id, iid)
                        .map(ApiHelpers::throwOnFailure)
                        .flatMap(mr -> {
                            if (mr.labelNames == null || mr.labelNames.isEmpty()) {
                                return Single.just(mr);
                            }
                            return issueService.getLabels(project.id, 1, 100)
                                    .map(r -> {
                                        if (r.isSuccessful() && r.body() != null) {
                                            enrichLabelColors(mr.labelNames, r.body());
                                        }
                                        return mr;
                                    })
                                    .onErrorReturn(e -> mr);
                        }));
    }

    private static void enrichLabelColors(List<GitLabLabel> mrLabels,
            List<GitLabLabel> projectLabels) {
        Map<String, GitLabLabel> byName = new HashMap<>();
        for (GitLabLabel pl : projectLabels) {
            if (pl.name != null) byName.put(pl.name, pl);
        }
        for (GitLabLabel ml : mrLabels) {
            GitLabLabel full = byName.get(ml.name);
            if (full != null) {
                ml.color = full.color;
                ml.textColor = full.textColor;
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Todos (notifications equivalent)
    // ---------------------------------------------------------------------------

    /**
     * Returns all pending GitLab todos for the authenticated user, grouped into a
     * {@link NotificationListLoadResult} compatible structure.
     */
    public static Single<NotificationListLoadResult> getNotifications(boolean all,
            boolean participating, boolean bypassCache) {
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, bypassCache);
        String state = all ? null : "pending";

        return service.listTodosByState(state, 1, 100)
                .map(ApiHelpers::throwOnFailure)
                .map(SingleFactory::todosToResult);
    }

    private static NotificationListLoadResult todosToResult(List<GitLabTodo> todos) {
        // Group todos by project
        Map<Long, List<GitLabTodo>> byProject = new HashMap<>();
        for (GitLabTodo todo : todos) {
            long projectId = todo.project != null ? todo.project.id : 0L;
            List<GitLabTodo> list = byProject.get(projectId);
            if (list == null) {
                list = new ArrayList<>();
                byProject.put(projectId, list);
            }
            list.add(todo);
        }

        // Build holder list — one repo header per project group, then individual todos.
        // Todos without a project (e.g. snippet todos, admin todos) are emitted without a header.
        List<NotificationHolder> result = new ArrayList<>();
        for (List<GitLabTodo> group : byProject.values()) {
            if (group.isEmpty()) continue;
            GitLabProject project = group.get(0).project;
            if (project != null) {
                NotificationHolder repoItem = new NotificationHolder(project);
                result.add(repoItem);

                boolean allRead = true;
                for (int i = 0; i < group.size(); i++) {
                    GitLabTodo todo = group.get(i);
                    NotificationHolder item = new NotificationHolder(todo);
                    item.setIsLastRepositoryNotification(i == group.size() - 1);
                    if (todo.isUnread()) allRead = false;
                    result.add(item);
                }
                repoItem.setIsRead(allRead);
            } else {
                // Project-less todos (snippets, admin-level): emit without a repo header row.
                for (int i = 0; i < group.size(); i++) {
                    GitLabTodo todo = group.get(i);
                    NotificationHolder item = new NotificationHolder(todo);
                    item.setIsLastRepositoryNotification(i == group.size() - 1);
                    result.add(item);
                }
            }
        }

        return new NotificationListLoadResult(result);
    }
}
