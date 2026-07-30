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

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.CommitCompareFragment;
import com.gl4a.fragment.ConfirmationDialogFragment;
import com.gl4a.fragment.PullRequestConversationFragment;
import com.gl4a.fragment.PullRequestFilesFragment;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.BottomSheetCompatibleScrollingViewBehavior;
import com.gl4a.widget.IssueStateTrackingFloatingActionButton;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class PullRequestActivity extends BaseFragmentPagerActivity implements
        View.OnClickListener, ConfirmationDialogFragment.Callback,
        PullRequestFilesFragment.CommentUpdateListener {

    public static Intent makeIntent(Context context, String repoOwner, String repoName, int number) {
        return makeIntent(context, repoOwner, repoName, number, -1, null);
    }

public static Intent makeIntent(Context context, String repoOwner, String repoName,
            int number, int initialPage, IntentUtils.InitialCommentMarker initialComment) {
        return new Intent(context, PullRequestActivity.class)
                .putExtra("owner", repoOwner)
                .putExtra("repo", repoName)
                .putExtra("number", number)
                .putExtra("initial_page", initialPage)
                .putExtra("initial_comment", initialComment);
    }

    public static final int PAGE_CONVERSATION = 0;
    public static final int PAGE_COMMITS = 1;
    public static final int PAGE_FILES = 2;

    private final ActivityResultLauncher<Intent> mEditIssueLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                setResult(Activity.RESULT_OK);
                onRefresh();
            })
    );

    private String mRepoOwner;
    private String mRepoName;
    private int mMergeRequestNumber;
    private int mInitialPage;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private Boolean mIsCollaborator;

    private GitLabMergeRequest mMergeRequest;
    private PullRequestConversationFragment mConversationFragment;
    private IssueStateTrackingFloatingActionButton mEditFab;

    private ViewGroup mHeader;
    private int[] mHeaderColorAttrs;

    private final ActivityResultLauncher<Intent> mCreateReviewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                if (mConversationFragment != null) {
                    mConversationFragment.reloadEvents(false);
                }
            })
    );

    private static final int[] TITLES = new int[]{
            R.string.pull_request_conversation, R.string.commits, R.string.pull_request_files
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LayoutInflater inflater =
                LayoutInflater.from(new ContextThemeWrapper(this, R.style.HeaderTheme));
        mHeader = (ViewGroup) inflater.inflate(R.layout.issue_header, null);
        mHeader.setClickable(false);
        mHeader.setVisibility(View.GONE);
        addHeaderView(mHeader, !hasTabsInToolbar());

        setContentShown(false);
        load(false);
    }

    @NonNull
    protected String getActionBarTitle() {
        return getString(R.string.pull_request_title) + " #" + mMergeRequestNumber;
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
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.pullrequest_menu, menu);

        Gl4Application app = Gl4Application.get();
        boolean authorized = app.isAuthorized();

        boolean isCreator = mMergeRequest != null
                && ApiHelpers.loginEquals(mMergeRequest.user(), app.getAuthLogin());
        boolean isClosed = mMergeRequest != null
                && ("closed".equals(mMergeRequest.state()) || "merged".equals(mMergeRequest.state()));
        boolean isMerged = mMergeRequest != null && mMergeRequest.isMerged();
        boolean isCollaborator = mIsCollaborator != null && mIsCollaborator;
        boolean canClose = mMergeRequest != null && authorized && (isCreator || isCollaborator);
        boolean canOpen = canClose && isCollaborator;
        boolean canMerge = canClose && isCollaborator && !mMergeRequest.isDraft();

        if (!canClose || isClosed) {
            menu.removeItem(R.id.pull_close);
        }
        if (!canOpen || !isClosed || isMerged) {
            menu.removeItem(R.id.pull_reopen);
        }
        if (!canMerge || isClosed) {
            menu.removeItem(R.id.pull_merge);
        }

        if (mMergeRequest == null) {
            menu.removeItem(R.id.share);
            menu.removeItem(R.id.browser);
            menu.removeItem(R.id.copy_number);
        }
        // GitLab has no pending-review concept in the same form; remove review menu item
        menu.removeItem(R.id.pull_review);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.pull_merge:
                showMergeConfirmDialog();
                break;
            case R.id.pull_close:
            case R.id.pull_reopen:
                showOpenCloseConfirmDialog(item.getItemId() == R.id.pull_reopen);
                break;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_pull_subject,
                        mMergeRequest.number(), mMergeRequest.title(),
                        mRepoOwner + "/" + mRepoName), Uri.parse(mMergeRequest.htmlUrl()));
                break;
            case R.id.browser:
                IntentUtils.launchBrowser(this, Uri.parse(mMergeRequest.htmlUrl()));
                break;
            case R.id.copy_number:
                IntentUtils.copyToClipboard(this, "Merge Request !" + mMergeRequest.number(),
                        String.valueOf(mMergeRequest.number()));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString("owner");
        mRepoName = extras.getString("repo");
        mMergeRequestNumber = extras.getInt("number");
        mInitialComment = extras.getParcelable("initial_comment");
        mInitialPage = extras.getInt("initial_page", -1);
        extras.remove("initial_comment");
        extras.remove("initial_page");
    }

    @Override
    public void onRefresh() {
        mMergeRequest = null;
        mIsCollaborator = null;
        setContentShown(false);
        if (mEditFab != null) {
            mEditFab.post(this::updateFabVisibility);
        }
        mHeader.setVisibility(View.GONE);
        mHeaderColorAttrs = null;
        load(true);
        invalidateTabs();
        supportInvalidateOptionsMenu();
        super.onRefresh();
    }

    @Override
    protected int[] getTabTitleResIds() {
        return mMergeRequest != null && mIsCollaborator != null ? TITLES : null;
    }

    @Override
    protected int[] getHeaderColorAttrs() {
        return mHeaderColorAttrs;
    }

    @Override
    protected Fragment makeFragment(int position) {
        if (position == PAGE_COMMITS) {
            // Use sourceBranch/targetBranch directly — the GitLab MR API does not populate
            // head/base nested objects with sha/label in the same way as GitHub.
            // Use diffRefs.baseSha for the base commit SHA if available.
            String baseSha = mMergeRequest.diffRefs != null
                    ? mMergeRequest.diffRefs.baseSha : null;
            String headSha = mMergeRequest.sha; // top-of-source-branch SHA
            return CommitCompareFragment.newInstance(mRepoOwner, mRepoName, mMergeRequestNumber,
                    mMergeRequest.targetBranch, baseSha,
                    mMergeRequest.sourceBranch, headSha);
        } else if (position == PAGE_FILES) {
            return PullRequestFilesFragment.newInstance(mRepoOwner, mRepoName,
                    mMergeRequestNumber, mMergeRequest.sha);
        } else {
            Fragment f = PullRequestConversationFragment.newInstance(mMergeRequest,
                    mRepoOwner, mRepoName, null, mIsCollaborator, mInitialComment);
            mInitialComment = null;
            return f;
        }
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        if (position == PAGE_CONVERSATION) {
            mConversationFragment = (PullRequestConversationFragment) f;
        }
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mConversationFragment) {
            mConversationFragment = null;
        }
    }

    @Override
    protected boolean fragmentNeedsRefresh(Fragment object) {
        return true;
    }

    @Override
    protected Intent navigateUp() {
        return IssueListActivity.makeIntent(this, mRepoOwner, mRepoName, true);
    }

    @Override
    public void onCommentsUpdated() {
        if (mConversationFragment != null) {
            mConversationFragment.reloadEvents(true);
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mEditFab) {
            // MR edit is not currently supported via IssueEditActivity; guard null to avoid NPE
            if (mMergeRequest == null) return;
            // TODO: implement a dedicated MR edit activity
            // For now, skip launching to avoid NPE from passing null GitLabIssue
            return;
        } else if (v.getId() == R.id.iv_gravatar) {
            Intent intent = UserActivity.makeIntent(this, (GitLabUser) v.getTag());
            if (intent != null) {
                startActivity(intent);
            }
        }
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        Bundle bundle = (Bundle) data;
        if ("opencloseconfirm".equals(tag)) {
            boolean reopen = bundle.getBoolean("reopen");
            updateMergeRequestState(reopen);
        } else if ("mergeconfirm".equals(tag)) {
            mergeMergeRequest();
        }
    }

    private void showOpenCloseConfirmDialog(final boolean reopen) {
        @StringRes int messageResId = reopen
                ? R.string.reopen_pull_request_confirm : R.string.close_pull_request_confirm;
        @StringRes int buttonResId = reopen
                ? R.string.pull_request_reopen : R.string.pull_request_close;
        Bundle data = new Bundle();
        data.putBoolean("reopen", reopen);

        ConfirmationDialogFragment.show(this, messageResId, buttonResId, true, data, "opencloseconfirm");
    }

    private void showMergeConfirmDialog() {
        Bundle data = new Bundle();
        ConfirmationDialogFragment.show(this, R.string.pull_request_merge,
                R.string.pull_request_merge, true, data, "mergeconfirm");
    }

    private void updateFabVisibility() {
        // MR editing is not yet implemented; hide the FAB rather than showing a non-functional button.
        // Re-enable this block and remove the early return once a dedicated MR-edit activity exists.
        if (mEditFab != null) {
            getRootLayout().removeView(mEditFab);
            adjustTabsForHeaderAlignedFab(false);
            mEditFab = null;
        }
    }

    private void fillHeader() {
        final int stateTextResId;

        if (mMergeRequest.isMerged()) {
            stateTextResId = R.string.pull_request_merged;
            mHeaderColorAttrs = new int[] {
                R.attr.colorPullRequestMerged, R.attr.colorPullRequestMergedDark
            };
        } else if ("closed".equals(mMergeRequest.state())) {
            stateTextResId = R.string.closed;
            mHeaderColorAttrs = new int[] {
                R.attr.colorIssueClosed, R.attr.colorIssueClosedDark
            };
        } else if (mMergeRequest.isDraft()) {
            stateTextResId = R.string.draft;
            mHeaderColorAttrs = new int[] {
                R.attr.colorPullRequestDraft, R.attr.colorPullRequestDraftDark
            };
        } else {
            stateTextResId = R.string.open;
            mHeaderColorAttrs = new int[] {
                R.attr.colorIssueOpen, R.attr.colorIssueOpenDark
            };
        }

        TextView tvState = mHeader.findViewById(R.id.tv_state);
        tvState.setText(getString(stateTextResId).toUpperCase(Locale.getDefault()));

        TextView tvTitle = mHeader.findViewById(R.id.tv_title);
        tvTitle.setText(mMergeRequest.title());

        mHeader.setVisibility(View.VISIBLE);
    }

    private void handleMergeRequestUpdate() {
        if (mConversationFragment != null) {
            mConversationFragment.updateState(mMergeRequest);
        }
        fillHeader();
        updateFabVisibility();
        transitionHeaderToColor(mHeaderColorAttrs[0], mHeaderColorAttrs[1]);
        supportInvalidateOptionsMenu();
    }

    private void updateMergeRequestState(boolean open) {
        if (mMergeRequest == null || mMergeRequest.projectId <= 0) {
            handleLoadFailure(new IllegalStateException("Merge request not loaded or missing projectId"));
            return;
        }
        @StringRes int dialogMessageResId = open ? R.string.opening_msg : R.string.closing_msg;
        @StringRes int errorMessageResId = open ? R.string.issue_error_reopen : R.string.issue_error_close;
        String errorMessage = getString(errorMessageResId, mMergeRequest.number());

        GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
        Map<String, Object> body = new HashMap<>();
        body.put("state_event", open ? "reopen" : "close");

        service.editMergeRequest(mMergeRequest.projectId, mMergeRequestNumber, body)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, dialogMessageResId, errorMessage))
                .subscribe(result -> {
                    mMergeRequest = result;
                    handleMergeRequestUpdate();
                }, error -> handleActionFailure("Updating merge request failed", error));
    }

    private void mergeMergeRequest() {
        String errorMessage = getString(R.string.pull_error_merge, mMergeRequest.number());
        GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
        Map<String, Object> body = new HashMap<>();
        body.put("merge_commit_message", mMergeRequest.title());

        service.mergeMergeRequest(mMergeRequest.projectId, mMergeRequestNumber, body)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.merging_msg, errorMessage))
                .subscribe(result -> {
                    mMergeRequest = result;
                    handleMergeRequestUpdate();
                }, error -> handleActionFailure("Merging merge request failed", error));
    }

    private void load(boolean force) {
        GitLabMergeRequestService mrService = ServiceFactory.get(GitLabMergeRequestService.class, force);

        // We need the project ID; for now derive it from owner/repo via the project service
        // The MR is fetched by iid using a project-lookup-then-fetch pattern.
        // For simplicity, store projectId once we have the MR.
        Single<Boolean> isCollaboratorSingle =
                SingleFactory.isAppUserRepoCollaborator(mRepoOwner, mRepoName, force)
                .subscribeOn(Schedulers.io());

        // GitLab MR needs a numeric project ID; use owner/repo path encoding (namespace%2Frepo)
        String projectPath = mRepoOwner + "%2F" + mRepoName;
        // Use project path as encoded ID (GitLab supports this)
        // We call a helper that looks up the project first, then fetches the MR.
        SingleFactory.getMergeRequest(mRepoOwner, mRepoName, mMergeRequestNumber, force)
                .subscribeOn(Schedulers.io())
                .zipWith(isCollaboratorSingle, Pair::create)
                .compose(makeLoaderSingle(0, force))
                .subscribe(result -> {
                    mMergeRequest = result.first;
                    mIsCollaborator = result.second;
                    fillHeader();
                    setContentShown(true);
                    // Guard against "Fragment already added" if invalidateTabs was
                    // already called (e.g. from onRefresh before load completes)
                    try { invalidateTabs(); } catch (IllegalStateException ignored) {}
                    updateFabVisibility();
                    supportInvalidateOptionsMenu();

                    if (mInitialPage >= 0 && mInitialPage < TITLES.length) {
                        getPager().setCurrentItem(mInitialPage);
                        mInitialPage = -1;
                    }
                }, this::handleLoadFailure);
    }

    @Nullable
    @Override
    protected Uri getActivityUri() {
        return IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("-")
                .appendPath("merge_requests")
                .appendPath(String.valueOf(mMergeRequestNumber))
                .build();
    }
}
