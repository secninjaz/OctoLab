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
        return new RepositoryAdapter(getActivity());
    }

    @Override
    public void onItemClick(GitLabProject item) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), item));
    }
}
