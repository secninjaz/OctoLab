package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.gitlab.model.GitLabMember;
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

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class OrganizationMemberListFragment extends PagedDataBaseFragment<GitLabUser> implements
        RootAdapter.OnItemClickListener<GitLabUser> {
    public static OrganizationMemberListFragment newInstance(String organization) {
        OrganizationMemberListFragment f = new OrganizationMemberListFragment();
        Bundle args = new Bundle();
        args.putString("org", organization);
        f.setArguments(args);
        return f;
    }

    @Override
    protected Single<Response<GitLabPage<GitLabUser>>> loadPage(int page, boolean bypassCache) {
        String organization = getArguments().getString("org");
        final GitLabGroupService service =
                ServiceFactory.get(GitLabGroupService.class, bypassCache);
        return service.getMembersByPath(organization, page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabUser>>error(
                                response.errorBody(), response.raw());
                    }
                    List<GitLabMember> members = response.body();
                    List<GitLabUser> users = new ArrayList<>(members.size());
                    for (GitLabMember m : members) {
                        users.add(m.toUser());
                    }
                    // Build a synthetic Response<List<GitLabUser>> carrying the original headers
                    // so ApiHelpers.toPage() can read X-Total-Pages and X-Next-Page correctly.
                    retrofit2.Response<List<GitLabUser>> userResponse =
                            retrofit2.Response.success(users, response.headers());
                    GitLabPage<GitLabUser> glPage = ApiHelpers.toPage(userResponse);
                    return Response.success(glPage);
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
        return R.string.no_org_members_found;
    }

    @Override
    public void onItemClick(GitLabUser item) {
        startActivity(UserActivity.makeIntent(getActivity(), item));
    }
}
