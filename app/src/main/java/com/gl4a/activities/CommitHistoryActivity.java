package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.CommitListFragment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.utils.SingleFactory;

public class CommitHistoryActivity extends FragmentContainerActivity implements
        CommitListFragment.ContextSelectionCallback {
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
                                    String ref, String path, String itemType,
                                    boolean supportBaseSelection) {
        return new Intent(context, CommitHistoryActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("ref", ref)
                .putExtra("path", path)
                .putExtra("type", itemType)
                .putExtra("base_selectable", supportBaseSelection)
                .putExtra("project_id", -1L);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
                                    long projectId, String ref, String path, String itemType,
                                    boolean supportBaseSelection) {
        return new Intent(context, CommitHistoryActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("project_id", projectId)
                .putExtra("ref", ref)
                .putExtra("path", path)
                .putExtra("type", itemType)
                .putExtra("base_selectable", supportBaseSelection);
    }

    private String mRepoOwner;
    private String mRepoName;
    private long mProjectId = -1L;
    private String mRef;
    private String mFilePath;
    private String mType;
    private boolean mSupportBaseSelection;

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.history);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mFilePath;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mProjectId = extras.getLong("project_id", -1L);
        mRef = extras.getString("ref");
        mFilePath = extras.getString("path");
        mType = extras.getString("type");
        mSupportBaseSelection = extras.getBoolean("base_selectable");
    }

    @Override
    protected Fragment onCreateFragment() {
        // If projectId not passed, CommitListFragment will resolve it via SingleFactory
        return CommitListFragment.newInstance(mRepoOwner, mRepoName, mProjectId, mRef, mFilePath);
    }

    @Override
    protected Intent navigateUp() {
        return RepositoryActivity.makeIntent(this, mRepoOwner, mRepoName, mRef);
    }

    @Override
    public boolean baseSelectionAllowed() {
        return mSupportBaseSelection;
    }

    @Override
    public void onCommitSelectedAsBase(GitLabCommit commit) {
        Intent result = new Intent();
        // ContentListFragment.mFileHistoryLauncher reads the result under the key "commit"
        // as a Parcelable. Use the same key so the commit object is not silently dropped.
        result.putExtra("commit", commit);
        setResult(RESULT_OK, result);
        finish();
    }
}