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
 * Activity in your projects (GitLab's own, across them) or in your starred projects (built from
 * the most recent of them), as GitLab web's "Your projects" and "Starred projects" tabs.
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

    // Starred's merged activity, built on its first page and paged from for the next (#315)
    private volatile List<GitLabEvent> mStarredEvents;
    private static final int PER_PAGE = 25;

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        GitLabUserService userService = ServiceFactory.get(GitLabUserService.class, bypassCache);
        if (!SCOPE_STARRED.equals(mScope)) {
            // GitLab's own activity across your projects, as on GitLab web: one request a page,
            // not one per project and again for every page (#315)
            return userService.getEventsInOwnProjects(page, PER_PAGE)
                    .map(response -> response.isSuccessful()
                            ? Response.success(ApiHelpers.toPage(response))
                            : Response.<GitLabPage<GitLabEvent>>error(response.errorBody(),
                                    response.raw()));
        }
        List<GitLabEvent> built = mStarredEvents;
        Single<List<GitLabEvent>> events = page == 1 || built == null
                ? loadStarredEvents(bypassCache).doOnSuccess(list -> mStarredEvents = list)
                : Single.just(built);
        return events.map(list -> Response.success(pageOf(list, page)));
    }

    /**
     * GitLab has no single request for starred projects' activity: the 10 most recent of the 20
     * starred projects, merged, newest first. Their paths are kept for the rows.
     */
    private Single<List<GitLabEvent>> loadStarredEvents(boolean bypassCache) {
        GitLabProjectService projectService = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        GitLabUserService userService = ServiceFactory.get(GitLabUserService.class, bypassCache);
        return projectService.getStarredProjects(true, 1, 20)
                .<List<GitLabEvent>>flatMap(projectsResponse -> {
                    if (!projectsResponse.isSuccessful() || projectsResponse.body() == null
                            || projectsResponse.body().isEmpty()) {
                        return Single.<List<GitLabEvent>>just(new ArrayList<>());
                    }
                    List<GitLabProject> projects = projectsResponse.body();
                    com.gl4a.utils.ProjectPaths.putAll(projects);
                    int limit = Math.min(projects.size(), 10);
                    List<Single<List<GitLabEvent>>> eventSingles = new ArrayList<>();
                    for (int i = 0; i < limit; i++) {
                        long projectId = projects.get(i).id();
                        eventSingles.add(
                                userService.getProjectEvents(projectId, 1, 10)
                                        .subscribeOn(Schedulers.io())
                                        .map(r -> r.isSuccessful() && r.body() != null
                                                ? r.body() : new ArrayList<GitLabEvent>())
                        );
                    }
                    return Single.<List<GitLabEvent>, List<GitLabEvent>>zip(eventSingles, results -> {
                        List<GitLabEvent> merged = new ArrayList<>();
                        for (Object r : results) {
                            //noinspection unchecked
                            merged.addAll((List<GitLabEvent>) r);
                        }
                        merged.sort((a, b) -> {
                            if (a.createdAt == null || b.createdAt == null) return 0;
                            return b.createdAt.compareTo(a.createdAt);
                        });
                        return merged;
                    });
                });
    }

    private static GitLabPage<GitLabEvent> pageOf(List<GitLabEvent> merged, int page) {
        int from = (page - 1) * PER_PAGE;
        int to = Math.min(from + PER_PAGE, merged.size());
        List<GitLabEvent> pageItems = from < merged.size()
                ? new ArrayList<>(merged.subList(from, to)) : new ArrayList<>();
        int totalPages = (int) Math.ceil(merged.size() / (double) PER_PAGE);
        int nextPage = page < totalPages ? page + 1 : 0;
        return new GitLabPage<>(pageItems, page, nextPage, totalPages, merged.size());
    }
}
