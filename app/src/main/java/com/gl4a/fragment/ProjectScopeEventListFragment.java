package com.gl4a.fragment;

import android.os.Bundle;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ApiHelpers;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

/**
 * Shows events from multiple projects (Your Projects or Starred projects scope).
 * Fetches the project list first, then aggregates events from the first few projects
 * to approximate GitLab web's "Your Projects" and "Starred" activity tabs.
 */
public class ProjectScopeEventListFragment extends EventListFragment {

    public static final String SCOPE_MEMBER  = "member";   // Your Projects
    public static final String SCOPE_STARRED = "starred";  // Starred projects

    private String mScope;

    public static ProjectScopeEventListFragment newInstance(String scope) {
        ProjectScopeEventListFragment f = new ProjectScopeEventListFragment();
        Bundle args = new Bundle();
        args.putString("scope", scope);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mScope = getArguments().getString("scope", SCOPE_MEMBER);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        GitLabProjectService projectService = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        GitLabUserService userService = ServiceFactory.get(GitLabUserService.class, bypassCache);

        // Fetch project list based on scope
        Single<Response<List<GitLabProject>>> projectsSingle;
        if (SCOPE_STARRED.equals(mScope)) {
            projectsSingle = projectService.getStarredProjects(true, 1, 20);
        } else {
            projectsSingle = projectService.listProjects(true, 1, 20, "last_activity_at", "desc", null, false);
        }

        // For each project, fetch events and merge into a single page
        return projectsSingle
                .flatMap(projectsResponse -> {
                    if (!projectsResponse.isSuccessful() || projectsResponse.body() == null
                            || projectsResponse.body().isEmpty()) {
                        // No projects — return empty page
                        return Single.just(Response.success(
                                new GitLabPage<>(new ArrayList<>(), 1, 0, 1, 0)));
                    }

                    List<GitLabProject> projects = projectsResponse.body();
                    // Fetch events from up to 10 projects in parallel
                    int limit = Math.min(projects.size(), 10);
                    List<Single<List<GitLabEvent>>> eventSingles = new ArrayList<>();
                    for (int i = 0; i < limit; i++) {
                        long projectId = projects.get(i).id();
                        eventSingles.add(
                                userService.getProjectEvents(projectId, 1, 10)
                                        .subscribeOn(Schedulers.io())
                                        .map(r -> r.isSuccessful() && r.body() != null
                                                ? r.body() : new ArrayList<>())
                        );
                    }

                    return Single.zip(eventSingles, results -> {
                        List<GitLabEvent> merged = new ArrayList<>();
                        for (Object r : results) {
                            //noinspection unchecked
                            merged.addAll((List<GitLabEvent>) r);
                        }
                        // Sort by creation date descending
                        merged.sort((a, b) -> {
                            if (a.createdAt == null || b.createdAt == null) return 0;
                            return b.createdAt.compareTo(a.createdAt);
                        });
                        // Apply pagination window
                        int perPage = 25;
                        int from = (page - 1) * perPage;
                        int to = Math.min(from + perPage, merged.size());
                        List<GitLabEvent> pageItems = from < merged.size()
                                ? merged.subList(from, to) : new ArrayList<>();
                        int totalPages = (int) Math.ceil(merged.size() / (double) perPage);
                        int nextPage = page < totalPages ? page + 1 : 0;
                        return Response.success(new GitLabPage<>(pageItems, page, nextPage, totalPages, merged.size()));
                    });
                });
    }
}
