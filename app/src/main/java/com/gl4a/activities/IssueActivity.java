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

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import androidx.appcompat.app.ActionBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gl4a.adapter.ItemsWithDescriptionAdapter;
import com.gl4a.utils.ActivityResultHelpers;
import com.google.android.material.appbar.AppBarLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.ConfirmationDialogFragment;
import com.gl4a.fragment.IssueFragment;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.widget.BottomSheetCompatibleScrollingViewBehavior;
import com.gl4a.widget.IssueStateTrackingFloatingActionButton;

import java.util.List;
import java.util.Locale;

public class IssueActivity extends BaseActivity implements
        View.OnClickListener, ConfirmationDialogFragment.Callback {
    public static Intent makeIntent(Context context, GitLabIssue issue, long projectId) {
        return makeIntent(context, null, null, projectId, issue.number(), null);
    }

    public static Intent makeIntent(Context context, String login, String repoName, int number) {
        return makeIntent(context, login, repoName, -1L, number, null);
    }

    public static Intent makeIntent(Context context, String login, String repoName,
            int number, IntentUtils.InitialCommentMarker initialComment) {
        return makeIntent(context, login, repoName, -1L, number, initialComment);
    }

    public static Intent makeIntent(Context context, String login, String repoName,
            long projectId, int number, IntentUtils.InitialCommentMarker initialComment) {
        return new Intent(context, IssueActivity.class)
                .putExtra("owner", login)
                .putExtra("repo", repoName)
                .putExtra("project_id", projectId)
                .putExtra("number", number)
                .putExtra("initial_comment", initialComment);
    }

    private static final int ID_LOADER_ISSUE = 0;
    private static final int ID_LOADER_COLLABORATOR_STATUS = 1;

    private final ActivityResultLauncher<Intent> mEditIssueLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                onRefresh();
                setResult(RESULT_OK);
            }));

    private GitLabIssue mIssue;
    private String mRepoOwner;
    private String mRepoName;
    private long mProjectId;
    private int mIssueNumber;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private ViewGroup mHeader;
    private Boolean mIsCollaborator;
    private IssueStateTrackingFloatingActionButton mEditFab;
    private final Handler mHandler = new Handler();
    private IssueFragment mFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.frame_layout);
        setContentShown(false);

        LayoutInflater inflater =
                LayoutInflater.from(new ContextThemeWrapper(this, R.style.HeaderTheme));
        mHeader = (ViewGroup) inflater.inflate(R.layout.issue_header, null);
        mHeader.setClickable(false);
        mHeader.setVisibility(View.GONE);
        addHeaderView(mHeader, false);

        setToolbarScrollable(true);
        loadIssue(false);
        loadCollaboratorStatus(false);
    }

    @NonNull
    protected String getActionBarTitle() {
        return getString(R.string.issue) + " #" + mIssueNumber;
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner != null ? mRepoOwner + "/" + mRepoName : null;
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
        mProjectId = extras.getLong("project_id", -1L);
        mIssueNumber = extras.getInt("number");
        mInitialComment = extras.getParcelable("initial_comment");
        extras.remove("initial_comment");
    }

    private void showUiIfDone() {
        if (mIssue == null || mIsCollaborator == null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        var existingFragment = (IssueFragment) fm.findFragmentById(R.id.details);
        if (existingFragment != null) {
            setFragment(existingFragment);
        } else {
            IssueFragment newFragment = IssueFragment.newInstance(mRepoOwner, mRepoName,
                    mIssue, mIsCollaborator, mInitialComment);
            setFragment(newFragment);
            fm.beginTransaction()
                    .add(R.id.details, newFragment)
                    .commitAllowingStateLoss();
            mInitialComment = null;
        }

        updateHeader();
        updateFabVisibility();
        setContentShown(true);
    }

    private void setFragment(IssueFragment fragment) {
        mFragment = fragment;
        setChildScrollDelegate(fragment);
    }

    private void updateHeader() {
        TextView tvState = mHeader.findViewById(R.id.tv_state);
        boolean closed = "closed".equals(mIssue.state());
        int stateTextResId = closed ? R.string.closed : R.string.open;
        int stateColorAttributeId = closed ? R.attr.colorIssueClosed : R.attr.colorIssueOpen;

        tvState.setText(getString(stateTextResId).toUpperCase(Locale.getDefault()));
        transitionHeaderToColor(stateColorAttributeId,
                closed ? R.attr.colorIssueClosedDark : R.attr.colorIssueOpenDark);

        TextView tvTitle = mHeader.findViewById(R.id.tv_title);
        tvTitle.setText(mIssue.title());

        mHeader.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.issue_menu, menu);

        boolean authorized = Gl4Application.get().isAuthorized();
        boolean isCreator = mIssue != null && authorized &&
                ApiHelpers.loginEquals(mIssue.user(), Gl4Application.get().getAuthLogin());
        boolean isClosed = mIssue != null && "closed".equals(mIssue.state());
        boolean isCollaborator = mIsCollaborator != null && mIsCollaborator;
        boolean closerIsCreator = mIssue != null
                && ApiHelpers.userEquals(mIssue.user(), mIssue.closedBy());
        boolean canClose = mIssue != null && authorized && (isCreator || isCollaborator);
        boolean canOpen = canClose && (isCollaborator || closerIsCreator);

        if (!canClose || isClosed) {
            menu.removeItem(R.id.issue_close);
        }
        if (!canOpen || !isClosed) {
            menu.removeItem(R.id.issue_reopen);
        }

        if (mIssue == null) {
            menu.removeItem(R.id.browser);
            menu.removeItem(R.id.share);
            menu.removeItem(R.id.copy_number);
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    protected Intent navigateUp() {
        if (mRepoOwner == null || mRepoName == null) {
            return null;
        }
        return IssueListActivity.makeIntent(this, mRepoOwner, mRepoName);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        switch (itemId) {
            case R.id.issue_close:
                if (checkForAuthOrExit()) {
                    showCloseReasonDialog();
                }
                return true;
            case R.id.issue_reopen:
                if (checkForAuthOrExit()) {
                    showReopenConfirmDialog();
                }
                return true;
            case R.id.share:
                IntentUtils.share(this, getString(R.string.share_issue_subject,
                        mIssueNumber, mIssue.title(), mRepoOwner + "/" + mRepoName),
                        Uri.parse(mIssue.htmlUrl()));
                return true;
            case R.id.browser:
                IntentUtils.launchBrowser(this, Uri.parse(mIssue.htmlUrl()));
                return true;
            case R.id.copy_number:
                IntentUtils.copyToClipboard(this, "Issue #" + mIssueNumber,
                        String.valueOf(mIssueNumber));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRefresh() {
        mIssue = null;
        mIsCollaborator = null;
        setContentShown(false);

        transitionHeaderToColor(androidx.appcompat.R.attr.colorPrimary, androidx.appcompat.R.attr.colorPrimaryDark);
        mHeader.setVisibility(View.GONE);

        if (mFragment != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(mFragment)
                    .commit();
            setFragment(null);
        }

        // onRefresh() can be triggered in the draw loop, and CoordinatorLayout doesn't
        // like its child list being changed while drawing
        mHandler.post(this::updateFabVisibility);

        supportInvalidateOptionsMenu();
        loadIssue(true);
        loadCollaboratorStatus(true);
        super.onRefresh();
    }

    @Override
    public void onBackPressed() {
        if (mFragment != null && mFragment.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        reopenIssue();
    }

    private void showReopenConfirmDialog() {
        ConfirmationDialogFragment.show(this, R.string.reopen_issue_confirm,
                R.string.pull_request_reopen, null, "reopenconfirm");
    }

    private void showCloseReasonDialog() {
        new CloseReasonDialogFragment().show(getSupportFragmentManager(), "close_reason");
    }

    private void reopenIssue() {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("state_event", "reopen");

        String failureMessage = getString(R.string.issue_error_reopen, mIssueNumber);
        service.editIssue(mProjectId, mIssueNumber, body)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.opening_msg, failureMessage))
                .subscribe(this::updateUiAfterStateUpdate, error -> handleActionFailure("Reopening issue failed", error));
    }

    private void closeIssue(String reason) {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("state_event", "close");
        // GitLab API v4 does not support state_reason; ignore the reason parameter

        String failureMessage = getString(R.string.issue_error_close, mIssueNumber);
        service.editIssue(mProjectId, mIssueNumber, body)
                .map(ApiHelpers::throwOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(this, R.string.closing_msg, failureMessage))
                .subscribe(this::updateUiAfterStateUpdate, error -> handleActionFailure("Closing issue failed", error));
    }

    private void updateUiAfterStateUpdate(GitLabIssue updatedIssue) {
        mIssue = updatedIssue;

        updateHeader();
        if (mEditFab != null) {
            mEditFab.setState(mIssue.state());
        }
        if (mFragment != null) {
            mFragment.updateState(mIssue);
        }
        setResult(RESULT_OK);
        supportInvalidateOptionsMenu();
    }

    private void updateFabVisibility() {
        boolean isIssueOwner = mIssue != null
                && ApiHelpers.loginEquals(mIssue.user(), Gl4Application.get().getAuthLogin());
        boolean isCollaborator = mIsCollaborator != null && mIsCollaborator;
        boolean shouldHaveFab = (isIssueOwner || isCollaborator) && mIssue != null;
        CoordinatorLayout rootLayout = getRootLayout();

        if (shouldHaveFab && mEditFab == null) {
            mEditFab = (IssueStateTrackingFloatingActionButton)
                    getLayoutInflater().inflate(R.layout.issue_edit_fab, rootLayout, false);
            mEditFab.setOnClickListener(this);
            rootLayout.addView(mEditFab);
        } else if (!shouldHaveFab && mEditFab != null) {
            rootLayout.removeView(mEditFab);
            mEditFab = null;
        }
        if (mEditFab != null) {
            mEditFab.setState(mIssue.state());
        }
    }

    private boolean checkForAuthOrExit() {
        if (Gl4Application.get().isAuthorized()) {
            return true;
        }
        Intent intent = new Intent(this, GitLabLoginActivity.class);
        startActivity(intent);
        finish();
        return false;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.edit_fab && checkForAuthOrExit()) {
            // Pass mIssue which already has projectId set; makeEditIntent copies issue.projectId
            // into EXTRA_KEY_PROJECT_ID so IssueEditActivity always gets a valid project ID.
            Intent editIntent = IssueEditActivity.makeEditIntent(this,
                    mRepoOwner, mRepoName, mIssue);
            mEditIssueLauncher.launch(editIntent);
        }
    }

    private void loadIssue(boolean force) {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, force);
        // When opened from a cross-reference link, mProjectId is -1L but mRepoOwner/Name
        // are set (from LinkParser). Resolve the project ID first, then load the issue.
        io.reactivex.Single<Long> projectIdSingle = mProjectId > 0
                ? io.reactivex.Single.just(mProjectId)
                : SingleFactory.getProjectId(mRepoOwner, mRepoName)
                        .doOnSuccess(id -> mProjectId = id);
        projectIdSingle
                .flatMap(pid -> service.getIssueWithLabels(pid, mIssueNumber, true)
                        .map(ApiHelpers::throwOnFailure)
                        .map(issues -> {
                            if (issues == null || issues.isEmpty()) {
                                throw new RuntimeException("Issue not found: " + mIssueNumber);
                            }
                            return issues.get(0);
                        }))
                .compose(makeLoaderSingle(ID_LOADER_ISSUE, force))
                .subscribe(result -> {
                    mIssue = result;
                    if (mIssue.projectId > 0) {
                        mProjectId = mIssue.projectId;
                    }
                    // When opened via the projectId-only path (from issue list), owner/repo
                    // are null. Fetch the project to get the display name (nameWithNamespace)
                    // so the subtitle matches the issue list exactly.
                    if ((mRepoOwner == null || mRepoName == null) && mProjectId > 0) {
                        ServiceFactory.get(
                                com.gl4a.gitlab.service.GitLabProjectService.class, false)
                            .getProject(mProjectId)
                            .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                            .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                            .subscribe(resp -> {
                                if (resp.isSuccessful() && resp.body() != null) {
                                    com.gl4a.gitlab.model.GitLabProject proj = resp.body();
                                    // Use pathWithNamespace ("r-n-d/ai-orchestration/cmmi-standards")
                                    // split at the last '/' to get the full owner namespace path
                                    // and repo name — works correctly for nested groups.
                                    // namespace.path only returns the immediate parent, not full path.
                                    if (proj.pathWithNamespace != null
                                            && proj.pathWithNamespace.contains("/")) {
                                        int slash = proj.pathWithNamespace.lastIndexOf('/');
                                        mRepoOwner = proj.pathWithNamespace.substring(0, slash);
                                        mRepoName = proj.pathWithNamespace.substring(slash + 1);
                                    } else if (proj.path != null) {
                                        mRepoName = proj.path;
                                    }
                                    ActionBar ab = getSupportActionBar();
                                    if (ab != null) ab.setSubtitle(proj.displayName());
                                }
                            }, err -> { /* best-effort — subtitle stays blank */ });
                    }
                    showUiIfDone();
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    private void loadCollaboratorStatus(boolean force) {
        // Guard: only call the collaborator-status API when we have both owner and repo name.
        // When the activity is opened via the projectId-only path (makeIntent(ctx,issue,projectId)),
        // mRepoOwner and mRepoName are null; passing nulls to isAppUserRepoCollaborator causes an
        // NPE inside the API call.  In that case we treat the user as non-collaborator and proceed.
        if (mRepoOwner == null || mRepoName == null) {
            mIsCollaborator = false;
            showUiIfDone();
            supportInvalidateOptionsMenu();
            return;
        }
        SingleFactory.isAppUserRepoCollaborator(mRepoOwner, mRepoName, force)
                .compose(makeLoaderSingle(ID_LOADER_COLLABORATOR_STATUS, force))
                .subscribe(result -> {
                    mIsCollaborator = result;
                    showUiIfDone();
                    supportInvalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    @Nullable
    @Override
    protected Uri getActivityUri() {
        return IntentUtils.createBaseUriForRepo(mRepoOwner, mRepoName)
                .appendPath("issues")
                .appendPath(String.valueOf(mIssueNumber))
                .build();
    }

    public static class CloseReasonDialogFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            var choiceItems = List.of(
                    new ItemsWithDescriptionAdapter.Item(
                            getString(R.string.issue_reason_completed),
                            getString(R.string.issue_reason_completed_description)),
                    new ItemsWithDescriptionAdapter.Item(
                            getString(R.string.issue_reason_not_planned),
                            getString(R.string.issue_reason_not_planned_description)),
                    new ItemsWithDescriptionAdapter.Item(
                            getString(R.string.issue_reason_duplicate),
                            getString(R.string.issue_reason_duplicate_description))
            );
            var reasonForItems = List.of("completed", "not_planned", "duplicate");

            IssueActivity activity = (IssueActivity) requireActivity();
            return new AlertDialog.Builder(activity)
                    .setTitle(R.string.close_issue_dialog_title)
                    .setAdapter(
                        new ItemsWithDescriptionAdapter(activity, choiceItems),
                        (dialog, itemIndex) -> {
                            activity.closeIssue(reasonForItems.get(itemIndex));
                        }
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .create();
        }
    }
}
