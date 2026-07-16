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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.CommitHistoryActivity;
import com.gl4a.adapter.FileAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabTreeItem;
import com.gl4a.gitlab.service.GitLabRepositoryService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.DownloadUtils;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.widget.ContextMenuAwareRecyclerView;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.reactivex.Single;

public class ContentListFragment extends ListDataBaseFragment<GitLabTreeItem> implements
        RootAdapter.OnItemClickListener<GitLabTreeItem> {

    public interface ParentCallback {
        void onContentsLoaded(ContentListFragment fragment, List<GitLabTreeItem> contents);
        void onTreeSelected(GitLabTreeItem content);
        void onCommitSelected(GitLabCommit commit);
        Set<String> getSubModuleNames(ContentListFragment fragment);
    }

    private static final Comparator<GitLabTreeItem> COMPARATOR = (lhs, rhs) -> {
        boolean lhsIsDir = lhs.isDirectory();
        boolean rhsIsDir = rhs.isDirectory();
        if (lhsIsDir && !rhsIsDir) {
            return -1;
        } else if (!lhsIsDir && rhsIsDir) {
            return 1;
        } else {
            return lhs.name().compareTo(rhs.name());
        }
    };

    private GitLabProject mRepository;
    private String mPath;
    private String mRef;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ParentCallback mCallback;
    private FileAdapter mAdapter;

    private final ActivityResultLauncher<Intent> mFileHistoryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    final GitLabCommit commit =
                            result.getData().getParcelableExtra("commit");
                    mHandler.post(() -> mCallback.onCommitSelected(commit));
                }
            });

    public static ContentListFragment newInstance(GitLabProject repository,
            String path, ArrayList<GitLabTreeItem> contents, String ref) {
        ContentListFragment f = new ContentListFragment();

        Bundle args = new Bundle();
        args.putString("path", path != null ? path : "");
        args.putString("ref", ref);
        args.putParcelable("repo", repository);
        args.putParcelableArrayList("contents", contents);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = getArguments().getParcelable("repo");
        mPath = getArguments().getString("path");
        mRef = getArguments().getString("ref");
        if (StringUtils.isBlank(mRef)) {
            mRef = mRepository.defaultBranch();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof ParentCallback) {
            mCallback = (ParentCallback) getParentFragment();
        } else if (context instanceof ParentCallback) {
            mCallback = (ParentCallback) context;
        } else {
            throw new ClassCastException("No callback provided");
        }
    }

    @Override
    protected RootAdapter<GitLabTreeItem, ?> onCreateAdapter() {
        mAdapter = new FileAdapter(getActivity());
        mAdapter.setSubModuleNames(mCallback.getSubModuleNames(this));
        mAdapter.setContextMenuSupported(true);
        mAdapter.setOnItemClickListener(this);
        return mAdapter;
    }

    @Override
    protected void onRecyclerViewInflated(RecyclerView view, LayoutInflater inflater) {
        super.onRecyclerViewInflated(view, inflater);
        registerForContextMenu(view);
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_files_found;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        var info = (ContextMenuAwareRecyclerView.RecyclerContextMenuInfo) menuInfo;
        GitLabTreeItem item = mAdapter.getItemFromAdapterPosition(info.position);
        Set<String> subModules = mCallback.getSubModuleNames(this);
        boolean isSubModule = subModules.contains(item.name());

        menu.add(Menu.NONE, R.id.history, Menu.NONE, R.string.history);
        if (item.isFile() && !isSubModule) {
            menu.add(Menu.NONE, R.id.download, Menu.NONE, R.string.download);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        ContextMenuAwareRecyclerView.RecyclerContextMenuInfo info =
                (ContextMenuAwareRecyclerView.RecyclerContextMenuInfo) item.getMenuInfo();
        if (info.position >= mAdapter.getItemCount()) {
            return false;
        }

        GitLabTreeItem treeItem = mAdapter.getItemFromAdapterPosition(info.position);

        switch (item.getItemId()) {
            case R.id.history: {
                String owner = mRepository.owner().login();
                String name = mRepository.name();
                Intent intent = CommitHistoryActivity.makeIntent(getActivity(),
                        owner, name, mRef, treeItem.path(), treeItem.type(), true);
                mFileHistoryLauncher.launch(intent);
                return true;
            }
            case R.id.download: {
                String owner = mRepository.owner().login();
                String name = mRepository.name();
                String url = IntentUtils.createRawFileUrl(owner, name, mRef, treeItem.path());
                DownloadUtils.enqueueDownloadWithPermissionCheck(getBaseActivity(),
                        url, FileUtils.getMimeTypeFor(treeItem.name()),
                        treeItem.name(), null);
                return true;
            }
        }

        return super.onContextItemSelected(item);
    }

    public String getPath() {
        return mPath;
    }

    public void onSubModuleNamesChanged(Set<String> subModules) {
        if (mAdapter != null) {
            mAdapter.setSubModuleNames(subModules);
        }
    }

    @Override
    protected void onAddData(RootAdapter<GitLabTreeItem, ?> adapter, List<GitLabTreeItem> data) {
        super.onAddData(adapter, data);
        mCallback.onContentsLoaded(this, data);
    }

    @Override
    public void onItemClick(GitLabTreeItem content) {
        mCallback.onTreeSelected(content);
    }

    @Override
    protected Single<List<GitLabTreeItem>> onCreateDataSingle(boolean bypassCache) {
        GitLabRepositoryService contentService =
                ServiceFactory.get(GitLabRepositoryService.class, bypassCache);
        long projectId = mRepository.id();
        String ref = mRef != null ? mRef : mRepository.defaultBranch();

        return contentService.getTree(projectId, mPath, ref, false, 1, 100)
                .map(response -> {
                    // 403: insufficient access (Guest on private repo) — return empty list
                    // 404: repo empty or path not found — return empty list
                    if (!response.isSuccessful() || response.body() == null) {
                        return new ArrayList<GitLabTreeItem>();
                    }
                    return (List<GitLabTreeItem>) response.body();
                })
                .compose(RxUtils.mapFailureToValue(
                        HttpURLConnection.HTTP_FORBIDDEN, new ArrayList<GitLabTreeItem>()))
                .compose(RxUtils.mapFailureToValue(
                        HttpURLConnection.HTTP_NOT_FOUND, new ArrayList<GitLabTreeItem>()))
                .compose(RxUtils.sortList(COMPARATOR));
    }

    @Override
    protected List<GitLabTreeItem> onGetInitialData() {
        ArrayList<GitLabTreeItem> contents = getArguments().getParcelableArrayList("contents");
        return contents != null && !contents.isEmpty() ? contents : null;
    }
}
