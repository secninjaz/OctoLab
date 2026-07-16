package com.gl4a.fragment;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.ReleaseInfoActivity;
import com.gl4a.adapter.ReleaseAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabRelease;
import com.gl4a.gitlab.service.GitLabReleaseService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class ReleaseListFragment extends PagedDataBaseFragment<GitLabRelease> implements
        RootAdapter.OnItemClickListener<GitLabRelease> {

    private String mRepoOwner;
    private String mRepoName;
    private long mProjectId;

    public static ReleaseListFragment newInstance(String owner, String repo) {
        ReleaseListFragment f = new ReleaseListFragment();
        Bundle args = new Bundle();
        args.putString("owner", owner);
        args.putString("repo", repo);
        // projectId is resolved at load time when the service call is made
        args.putLong("project_id", 0L);
        f.setArguments(args);
        return f;
    }

    public static ReleaseListFragment newInstance(String owner, String repo, long projectId) {
        ReleaseListFragment f = new ReleaseListFragment();
        Bundle args = new Bundle();
        args.putString("owner", owner);
        args.putString("repo", repo);
        args.putLong("project_id", projectId);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepoOwner = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        mProjectId = getArguments().getLong("project_id", 0L);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabRelease>>> loadPage(int page, boolean bypassCache) {
        final GitLabReleaseService service =
                ServiceFactory.get(GitLabReleaseService.class, bypassCache);

        Single<Long> idSingle = mProjectId > 0
                ? Single.just(mProjectId)
                : SingleFactory.getProjectId(mRepoOwner, mRepoName)
                        .doOnSuccess(id -> mProjectId = id);

        return idSingle.flatMap(projectId ->
                service.listReleases(projectId, page, 25)
                        .map(response -> {
                            if (response.isSuccessful()) {
                                GitLabPage<GitLabRelease> glPage = ApiHelpers.toPage(response);
                                return Response.success(glPage);
                            }
                            return Response.<GitLabPage<GitLabRelease>>error(
                                    response.errorBody(), response.raw());
                        }));
    }

    @Override
    protected RootAdapter<GitLabRelease, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        ReleaseAdapter adapter = new ReleaseAdapter(getActivity());
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_releases_found;
    }

    @Override
    public void onItemClick(GitLabRelease release) {
        startActivity(ReleaseInfoActivity.makeIntent(
                getActivity(), mRepoOwner, mRepoName, release.tagName()));
    }
}
