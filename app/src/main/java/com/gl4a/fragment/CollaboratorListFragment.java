package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.UserActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.UserAdapter;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import io.reactivex.Single;
import retrofit2.Response;

public class CollaboratorListFragment extends PagedDataBaseFragment<GitLabUser> implements
        RootAdapter.OnItemClickListener<GitLabUser> {
    public static CollaboratorListFragment newInstance(String owner, String repo) {
        CollaboratorListFragment f = new CollaboratorListFragment();
        Bundle args = new Bundle();
        args.putString("owner", owner);
        args.putString("repo", repo);
        f.setArguments(args);
        return f;
    }

    @Override
    protected Single<Response<GitLabPage<GitLabUser>>> loadPage(int page, boolean bypassCache) {
        String owner = getArguments().getString("owner");
        String repo = getArguments().getString("repo");
        final GitLabProjectService service =
                ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return SingleFactory.getProjectId(owner, repo)
                .flatMap(projectId -> service.getMembers(projectId, page, 25))
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabUser>>error(
                                response.errorBody(), response.raw());
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }

    @Override
    protected RootAdapter<GitLabUser, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        UserAdapter adapter = new UserAdapter(getActivity());
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_collaborators_found;
    }

    @Override
    public void onItemClick(GitLabUser item) {
        startActivity(UserActivity.makeIntent(getActivity(), item));
    }
}
