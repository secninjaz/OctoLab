package com.gl4a.fragment;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RepositoryAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ApiHelpers;

import io.reactivex.Single;
import retrofit2.Response;

/** All the projects a user has contributed to, in the Projects section's rows (#232). */
public class UserProjectListFragment extends PagedDataBaseFragment<GitLabProject> {
    public static UserProjectListFragment newInstance(long userId, String login) {
        UserProjectListFragment f = new UserProjectListFragment();
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        args.putString("login", login);
        f.setArguments(args);
        return f;
    }

    private long mUserId;
    private String mLogin;
    // That user's role in each listed project, filled page by page (#233)
    private final java.util.Map<Long, String> mRoles =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserId = getArguments().getLong("user_id");
        mLogin = getArguments().getString("login");
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        RepositoryAdapter adapter = new RepositoryAdapter(getActivity());
        adapter.setRoleSource(RepositoryAdapter.userRoles(mRoles));
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.user_no_contributed_projects;
    }

    @Override
    public void onItemClick(GitLabProject project) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), project));
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        return ServiceFactory.get(GitLabUserService.class, bypassCache)
                .getContributedProjects(mUserId, page, 25)
                .map(response -> {
                    if (!response.isSuccessful()) {
                        return Response.<GitLabPage<GitLabProject>>error(response.errorBody(),
                                response.raw());
                    }
                    if (response.body() != null) {
                        java.util.List<Long> ids = new java.util.ArrayList<>();
                        for (GitLabProject project : response.body()) ids.add(project.id);
                        mRoles.putAll(com.gl4a.utils.UserProjectRoles.load(mLogin, ids).roles);
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }
}
