package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.MergeRequestVersionFragment;

/**
 * The changes of one MR version (push) against the previous one, like GitLab web's "Compare
 * with previous version": the commit screen's file list and diffs, under a version header
 * (#198).
 */
public class MergeRequestVersionActivity extends FragmentContainerActivity {
    /**
     * @param versionNumber this version's number as GitLab web counts them (1 = first push),
     *        or 0 if unknown
     * @param previousNumber the compared version's number, or 0 to compare with the target
     *        branch
     */
    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int mergeRequestIid, String fromSha, String toSha, int versionNumber,
            int previousNumber) {
        return new Intent(context, MergeRequestVersionActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("mr_iid", mergeRequestIid)
                .putExtra("from", fromSha)
                .putExtra("to", toSha)
                .putExtra("version", versionNumber)
                .putExtra("previous", previousNumber);
    }

    private String mRepoOwner;
    private String mRepoName;
    private int mMergeRequestIid;
    private int mVersionNumber;

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return mVersionNumber > 0
                ? getString(R.string.mr_version_title, mVersionNumber)
                : getString(R.string.mr_version_title_unknown);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName + " !" + mMergeRequestIid;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mMergeRequestIid = extras.getInt("mr_iid");
        mVersionNumber = extras.getInt("version");
    }

    @Override
    protected Fragment onCreateFragment() {
        Bundle extras = getIntent().getExtras();
        return MergeRequestVersionFragment.newInstance(mRepoOwner, mRepoName,
                extras.getString("from"), extras.getString("to"),
                mVersionNumber, extras.getInt("previous"));
    }

    @Override
    protected Intent navigateUp() {
        return PullRequestActivity.makeIntent(this, mRepoOwner, mRepoName, mMergeRequestIid);
    }
}
