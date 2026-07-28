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
package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import com.google.android.material.appbar.AppBarLayout;
import androidx.fragment.app.Fragment;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.CommitFragment;
import com.gl4a.fragment.CommitCommentsFragment;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.widget.BottomSheetCompatibleScrollingViewBehavior;

import java.util.Collections;
import java.util.List;

import io.reactivex.Single;

public class CommitActivity extends BaseFragmentPagerActivity implements
        CommitFragment.CommentUpdateListener, CommitCommentsFragment.CommentUpdateListener {
    public static Intent makeIntent(Context context, String repoOwner, String repoName, String sha) {
        return makeIntent(context, repoOwner, repoName, -1, sha, null);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int mergeRequestIid, String sha) {
        return makeIntent(context, repoOwner, repoName, mergeRequestIid, sha, null);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            String sha, IntentUtils.InitialCommentMarker initialComment) {
        return makeIntent(context, repoOwner, repoName, -1, sha, initialComment);
    }

    public static Intent makeIntent(Context context, String repoOwner, String repoName,
            long projectId, String sha) {
        return new Intent(context, CommitActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("project_id", projectId)
                .putExtra("mr_iid", -1)
                .putExtra("sha", sha);
    }

    private static Intent makeIntent(Context context, String repoOwner, String repoName,
            int mergeRequestIid, String sha, IntentUtils.InitialCommentMarker initialComment) {
        return new Intent(context, CommitActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("mr_iid", mergeRequestIid)
                .putExtra("sha", sha)
                .putExtra("initial_comment", initialComment);
    }

    private static final int ID_LOADER_COMMIT = 0;
    private static final int ID_LOADER_COMMENTS = 1;
    private static final int ID_LOADER_DIFFS = 2;

    private String mRepoOwner;
    private String mRepoName;
    private String mObjectSha;
    private int mPullRequestNumber;

    private long mProjectId = -1L;
    private GitLabCommit mCommit;
    private List<GitLabComment> mComments;
    private List<GitLabDiff> mDiffs;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private CommitFragment mCommitFragment;

    private static final int[] TITLES = new int[] {
        R.string.commit, R.string.issue_comments
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentShown(false);
        loadCommit(false);
        loadComments(false);
        loadDiffs(false);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        if (mObjectSha == null || mObjectSha.length() < 7) return getString(R.string.commit_title, "");
        return getString(R.string.commit_title, mObjectSha.substring(0, 7));
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected AppBarLayout.ScrollingViewBehavior onCreateSwipeLayoutBehavior() {
        return new BottomSheetCompatibleScrollingViewBehavior();
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mObjectSha = extras.getString("sha");
        mProjectId = extras.getLong("project_id", -1L);
        mPullRequestNumber = extras.getInt("mr_iid", -1);
        mInitialComment = extras.getParcelable("initial_comment");
        extras.remove("initial_comment");
        // Guard: without a valid SHA the activity cannot load anything useful.
        if (android.text.TextUtils.isEmpty(mObjectSha)) {
            finish();
        }
    }

    @Override
    protected int[] getTabTitleResIds() {
        return mCommit != null && mComments != null ? TITLES : null;
    }

    @Override
    public void onRefresh() {
        mCommit = null;
        mComments = null;
        mDiffs = null;
        setContentShown(false);
        loadCommit(true);
        loadComments(true);
        loadDiffs(true);
        super.onRefresh();
    }

    @Override
    protected Fragment makeFragment(int position) {
        if (position == 1) {
            Fragment f = CommitCommentsFragment.newInstance(mRepoOwner, mRepoName, mObjectSha,
                    mCommit, mComments, mInitialComment);
            mInitialComment = null;
            return f;
        } else {
            mCommitFragment = CommitFragment.newInstance(mRepoOwner, mRepoName, mObjectSha,
                    mCommit, mComments);
            return mCommitFragment;
        }
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        if (position == 0 && f instanceof CommitFragment) {
            mCommitFragment = (CommitFragment) f;
            // If diffs already loaded before the fragment was created, push them now.
            if (mDiffs != null) {
                mCommitFragment.fillStatsFromDiffs(mDiffs, mComments);
            }
        }
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mCommitFragment) {
            mCommitFragment = null;
        }
    }

    @Override
    protected boolean fragmentNeedsRefresh(Fragment object) {
        return true;
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.commit_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    protected Intent navigateUp() {
        if (mPullRequestNumber > 0) {
            // TODO: rename to MergeRequestActivity once PullRequestActivity is migrated
            return PullRequestActivity.makeIntent(this, mRepoOwner, mRepoName, mPullRequestNumber);
        }
        return RepositoryActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Uri diffUri = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("commit")
                .appendPath(mObjectSha)
                .build();

        switch (item.getItemId()) {
            case R.id.browser:
                IntentUtils.launchBrowser(this, diffUri);
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_commit_subject,
                        mObjectSha.substring(0, 7), mRepoOwner + "/" + mRepoName), diffUri);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCommentsUpdated() {
        mComments = null;
        setResult(RESULT_OK);
        setContentShown(false);
        loadComments(true);
    }

    private void showContentIfReady() {
        if (mCommit != null && mComments != null) {
            setContentShown(true);
            invalidateTabs();
            if (mInitialComment != null) {
                getPager().setCurrentItem(1);
            }
        }
        // Push diffs to an already-shown CommitFragment whenever diffs become available.
        // This may fire after invalidateTabs() if diffs arrive later than commit+comments.
        if (mDiffs != null) {
            pushDiffsToFragment();
        }
    }

    private void pushDiffsToFragment() {
        if (mCommitFragment != null) {
            mCommitFragment.fillStatsFromDiffs(mDiffs, mComments);
        }
    }

    private Single<Long> resolveProjectId() {
        if (mProjectId > 0) {
            return Single.just(mProjectId);
        }
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .doOnSuccess(id -> mProjectId = id);
    }

    private void loadCommit(boolean force) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, force);

        resolveProjectId()
                .flatMap(projectId -> service.getCommit(projectId, mObjectSha)
                        .map(ApiHelpers::throwOnFailure))
                .<GitLabCommit>compose(makeLoaderSingle(ID_LOADER_COMMIT, force))
                .subscribe(result -> {
                    mCommit = result;
                    showContentIfReady();
                }, this::handleLoadFailure);
    }

    private void loadComments(boolean force) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, force);
        resolveProjectId()
                .flatMap(projectId -> service.getCommitComments(projectId, mObjectSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .<List<GitLabComment>>compose(makeLoaderSingle(ID_LOADER_COMMENTS, force))
                .subscribe(result -> {
                    mComments = result;
                    if (result.isEmpty()) {
                        mInitialComment = null;
                    }
                    showContentIfReady();
                }, this::handleLoadFailure);
    }

    private void loadDiffs(boolean force) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, force);
        resolveProjectId()
                .flatMap(projectId -> service.getCommitDiff(projectId, mObjectSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .<List<GitLabDiff>>compose(makeLoaderSingle(ID_LOADER_DIFFS, force))
                .subscribe(result -> {
                    mDiffs = result;
                    showContentIfReady();
                }, error -> {
                    // Non-fatal: diffs failing should not prevent the commit from displaying.
                    mDiffs = java.util.Collections.emptyList();
                    showContentIfReady();
                });
    }

    @Nullable
    @Override
    protected Uri getActivityUri() {
        Uri.Builder builder = IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName);
        if (mPullRequestNumber > 0) {
            builder.appendPath("-")
                    .appendPath("merge_requests")
                    .appendPath(String.valueOf(mPullRequestNumber));
        }
        return builder.appendPath("-")
                .appendPath("commit")
                .appendPath(mObjectSha)
                .build();
    }
}