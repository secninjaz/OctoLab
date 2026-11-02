package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabSearchService;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RepositoryAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.RxUtils;

import io.reactivex.Single;
import retrofit2.Response;

public class RepositorySearchFragment extends PagedDataBaseFragment<GitLabProject> {
    public static RepositorySearchFragment newInstance(String userLogin) {
        RepositorySearchFragment f = new RepositorySearchFragment();

        Bundle args = new Bundle();
        args.putString("user", userLogin);
        f.setArguments(args);

        return f;
    }

    /** Searched within your projects of this filter (#309). */
    public void setQuery(String query, String filterType) {
        getArguments().putString("filter_type", filterType);
        setQuery(query);
    }

    public void setQuery(String query) {
        getArguments().putString("query", query);
        if (isAdded()) {
            onRefresh();
        }
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        String login = getArguments().getString("user");
        String query = getArguments().getString("query");

        if (TextUtils.isEmpty(query)) {
            return Single.just(Response.success(new ApiHelpers.DummyPage<>()));
        }

        if (com.gl4a.utils.ApiHelpers.loginEquals(login,
                com.gl4a.Gl4Application.get().getAuthLogin())) {
            // Your own projects: within them, by the current filter, with GitLab's project list
            // (not the site-wide, rate-limited search; everyone else's are in Search, #309)
            java.util.Map<String, String> params =
                    RepositoryListFragment.scopeParams(getArguments().getString("filter_type"));
            params.put("search", query);
            params.put("order_by", "last_activity_at");
            return ServiceFactory.get(com.gl4a.gitlab.service.GitLabProjectService.class,
                            bypassCache)
                    .listProjectsWithParams(params, page, 25)
                    .map(response -> response.isSuccessful() && response.body() != null
                            ? ApiHelpers.toPage(response)
                            : new ApiHelpers.DummyPage<GitLabProject>())
                    .map(pageResult -> Response.<GitLabPage<GitLabProject>>success(pageResult));
        }

        GitLabSearchService service = ServiceFactory.get(GitLabSearchService.class, bypassCache);

        return service.searchProjects("projects", query, page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabProject>>success(new ApiHelpers.DummyPage<>());
                    }
                    GitLabPage<GitLabProject> resultPage = new GitLabPage<>(response.body(), 1, 0, 1, response.body().size());
                    return Response.<GitLabPage<GitLabProject>>success(resultPage);
                })
                .compose(RxUtils.mapFailureToValue(422, Response.success(new ApiHelpers.DummyPage<>())));
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_search_repos_found;
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        RepositoryAdapter adapter = new RepositoryAdapter(getActivity());
        adapter.showViewerRoles(); // your role in each project, as in Groups (#233)
        return adapter;
    }

    @Override
    public void onItemClick(GitLabProject item) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), item));
    }
}
