/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gl4a.fragment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.CommitActivity;
import com.gl4a.adapter.CommitAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;
import com.gl4a.utils.StringUtils;
import com.gl4a.widget.ContextMenuAwareRecyclerView;

import java.net.HttpURLConnection;
import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class CommitListFragment extends PagedDataBaseFragment<GitLabCommit> implements MenuProvider {
    public interface ContextSelectionCallback {
        boolean baseSelectionAllowed();
        void onCommitSelectedAsBase(GitLabCommit commit);
    }

    private String mRepoOwner;
    private String mRepoName;
    private long mProjectId;
    private String mRef;
    private String mFilePath;
    private boolean mFollowRenames;

    private CommitAdapter mAdapter;
    private ContextSelectionCallback mCallback;

    private final ActivityResultLauncher<Intent> mCommitLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> onRefresh())
    );

    public static CommitListFragment newInstance(GitLabProject repo, String ref) {
        return newInstance(repo.owner().login(), repo.name(), repo.id(),
                StringUtils.isBlank(ref) ? repo.defaultBranch() : ref, null);
    }

    public static CommitListFragment newInstance(String repoOwner, String repoName,
            long projectId, String ref, String filePath) {
        CommitListFragment f = new CommitListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putLong("project_id", projectId);
        args.putString("ref", ref);
        args.putString("path", filePath);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepoOwner = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        mProjectId = getArguments().getLong("project_id");
        mRef = getArguments().getString("ref");
        mFilePath = getArguments().getString("path");
        if (savedInstanceState != null) {
            mFollowRenames = savedInstanceState.getBoolean("follow_renames");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("follow_renames", mFollowRenames);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        if (mFilePath != null) {
            inflater.inflate(R.menu.commit_history_menu, menu);
        }
    }

    @Override
    public void onPrepareMenu(Menu menu) {
        MenuItem followItem = menu.findItem(R.id.follow_renames);
        if (followItem != null) {
            followItem.setChecked(mFollowRenames);
        }
    }

    @Override
    public boolean onMenuItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.follow_renames) {
            mFollowRenames = !mFollowRenames;
            item.setChecked(mFollowRenames);
            onRefresh();
            return true;
        }

        return false;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mCallback = context instanceof ContextSelectionCallback
                ? (ContextSelectionCallback) context : null;
        requireActivity().addMenuProvider(this);
    }

    @Override
    protected RootAdapter<GitLabCommit, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        mAdapter = new CommitAdapter(getActivity());
        mAdapter.setContextMenuSupported(mCallback != null && mCallback.baseSelectionAllowed());
        return mAdapter;
    }

    @Override
    protected void onRecyclerViewInflated(RecyclerView view, LayoutInflater inflater) {
        super.onRecyclerViewInflated(view, inflater);
        registerForContextMenu(view);
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_commits_found;
    }

    @Override
    public void onItemClick(GitLabCommit commit) {
        Intent intent = CommitActivity.makeIntent(getActivity(),
                mRepoOwner, mRepoName, commit.sha());
        mCommitLauncher.launch(intent);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.add(Menu.NONE, R.id.select_as_branch_ref, Menu.NONE, R.string.commit_use_as_ref);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ContextMenuAwareRecyclerView.RecyclerContextMenuInfo info =
                (ContextMenuAwareRecyclerView.RecyclerContextMenuInfo) item.getMenuInfo();
        if (info.position >= mAdapter.getItemCount()) {
            return false;
        }

        if (item.getItemId() == R.id.select_as_branch_ref) {
            GitLabCommit commit = mAdapter.getItemFromAdapterPosition(info.position);
            mCallback.onCommitSelectedAsBase(commit);
            return true;
        }

        return super.onContextItemSelected(item);
    }

    private Single<Long> resolveProjectId() {
        if (mProjectId > 0) {
            return Single.just(mProjectId);
        }
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .doOnSuccess(id -> mProjectId = id);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabCommit>>> loadPage(int page, boolean bypassCache) {
        final GitLabCommitService service =
                ServiceFactory.get(GitLabCommitService.class, bypassCache);

        return resolveProjectId()
                .flatMap(projectId -> service.listCommits(projectId, mRef, mFilePath, page, 25, null, null))
                .map(response -> {
                    // 409 is returned for empty repositories
                    if (response.code() == HttpURLConnection.HTTP_CONFLICT) {
                        return Response.success(new ApiHelpers.DummyPage<>());
                    }
                    if (response.isSuccessful()) {
                        GitLabPage<GitLabCommit> glPage = ApiHelpers.toPage(response);
                        return Response.success(glPage);
                    }
                    return Response.<GitLabPage<GitLabCommit>>error(
                            response.errorBody(), response.raw());
                });
    }
}
