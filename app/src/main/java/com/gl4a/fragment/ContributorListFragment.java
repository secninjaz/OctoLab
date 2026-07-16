package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.model.GitLabContributor;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.adapter.ContributorAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import io.reactivex.Single;
import retrofit2.Response;

public class ContributorListFragment extends PagedDataBaseFragment<GitLabContributor> implements
        RootAdapter.OnItemClickListener<GitLabContributor> {
    public static ContributorListFragment newInstance(String repoOwner, String repoName) {
        ContributorListFragment f = new ContributorListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        f.setArguments(args);

        return f;
    }

    @Override
    protected Single<Response<GitLabPage<GitLabContributor>>> loadPage(int page, boolean bypassCache) {
        String repoOwner = getArguments().getString("owner");
        String repoName = getArguments().getString("repo");
        final GitLabProjectService service =
                ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return SingleFactory.getProjectId(repoOwner, repoName)
                .flatMap(projectId -> service.getContributors(projectId, page, 25))
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        // Do NOT use response.raw() — the body may already be consumed.
                        throw new com.gl4a.ApiRequestException(response);
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_contributors_found;
    }

    @Override
    protected RootAdapter<GitLabContributor, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        ContributorAdapter adapter = new ContributorAdapter(getActivity());
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    public void onItemClick(GitLabContributor item) {
        // GitLabContributor has no user account link — nothing to navigate to
    }
}
