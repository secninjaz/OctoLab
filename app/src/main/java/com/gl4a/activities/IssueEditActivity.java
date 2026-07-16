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
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.gl4a.adapter.ItemsWithDescriptionAdapter;
import com.gl4a.utils.ActivityResultHelpers;
import com.google.android.material.appbar.AppBarLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;
import androidx.fragment.app.DialogFragment;
import androidx.core.content.ContextCompat;
import androidx.core.util.ObjectsCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.appcompat.app.AlertDialog;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.BasePagerActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabLabel;
import com.gl4a.gitlab.model.GitLabMilestone;
import com.gl4a.gitlab.model.GitLabTreeItem;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.service.GitLabRepositoryService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.MarkdownButtonsBar;
import com.gl4a.widget.MarkdownPreviewWebView;
import com.vdurmont.emoji.EmojiParser;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Flowable;
import io.reactivex.Single;
import retrofit2.Response;

import static java.util.stream.Collectors.toList;

public class IssueEditActivity extends BasePagerActivity implements
        AppBarLayout.OnOffsetChangedListener, View.OnClickListener,
        View.OnFocusChangeListener {
    public static Intent makeCreateIntent(Context context, String repoOwner, String repoName) {
        // Deprecated: prefer the overload that accepts projectId to avoid a redundant lookup.
        // This variant is kept for callers that do not know the projectId at call-site.
        // onInitExtras() will keep mProjectId=-1L and IssueListFragment/IssueEditActivity
        // will resolve it lazily via SingleFactory.getProjectId() on first API call.
        return new Intent(context, IssueEditActivity.class)
                .putExtra(EXTRA_KEY_OWNER, repoOwner)
                .putExtra(EXTRA_KEY_REPO, repoName);
    }

    public static Intent makeCreateIntent(Context context, String repoOwner, String repoName, String title, String body) {
        return new Intent(context, IssueEditActivity.class)
                .putExtra(EXTRA_KEY_OWNER, repoOwner)
                .putExtra(EXTRA_KEY_REPO, repoName)
                .putExtra(EXTRA_KEY_TITLE, title)
                .putExtra(EXTRA_KEY_BODY, body);
    }

    public static Intent makeCreateIntent(Context context, String repoOwner, String repoName,
            long projectId) {
        return new Intent(context, IssueEditActivity.class)
                .putExtra(EXTRA_KEY_OWNER, repoOwner)
                .putExtra(EXTRA_KEY_REPO, repoName)
                .putExtra(EXTRA_KEY_PROJECT_ID, projectId);
    }

    public static Intent makeEditIntent(Context context, String repoOwner,
            String repoName, GitLabIssue issue) {
        return new Intent(context, IssueEditActivity.class)
                .putExtra(EXTRA_KEY_OWNER, repoOwner)
                .putExtra(EXTRA_KEY_REPO, repoName)
                .putExtra(EXTRA_KEY_PROJECT_ID, issue.projectId)
                .putExtra(EXTRA_KEY_ISSUE_IID, issue.iid)
                .putExtra(EXTRA_KEY_ISSUE_TITLE, issue.title())
                .putExtra(EXTRA_KEY_ISSUE_BODY, issue.body());
    }

    private interface OnAssigneesLoaded {
        void handleLoad(List<GitLabUser> assignees);
    }
    private interface OnLabelsLoaded {
        void handleLoad(List<GitLabLabel> labels);
    }
    private interface OnMilestonesLoaded {
        void handleLoad(List<GitLabMilestone> milestones);
    }

    private static final int ID_LOADER_COLLABORATOR_STATUS = 0;
    private static final int[] TITLES = { R.string.issue_body, R.string.preview, R.string.settings };

    private final ActivityResultLauncher<Intent> mLabelManagerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> mLabelSingle = null)
    );
    private final ActivityResultLauncher<Intent> mMilestoneManagerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> mMilestoneSingle = null)
    );

    private String mRepoOwner;
    private String mRepoName;
    private long mProjectId = -1L;

    private boolean mIsCollaborator;

    private Single<List<GitLabUser>> mAssigneeSingle;
    private Single<List<GitLabLabel>> mLabelSingle;
    private Single<List<GitLabMilestone>> mMilestoneSingle;

    // Mutable edit state stored as simple fields
    private String mTitle;
    private String mBody;
    private GitLabMilestone mMilestone;
    private List<GitLabUser> mAssignees = new ArrayList<>();
    private List<GitLabLabel> mLabels = new ArrayList<>();

    // For editing: original iid and project-scoped id
    private int mIssueIid = -1;

    // Original values for diff on save
    private String mOriginalTitle;
    private String mOriginalBody;
    private GitLabMilestone mOriginalMilestone;
    private List<GitLabUser> mOriginalAssignees;
    private List<GitLabLabel> mOriginalLabels;

    private TextInputLayout mTitleWrapper;
    private EditText mTitleView;
    private EditText mDescView;
    private FloatingActionButton mFab;

    private View mRootView;
    private MarkdownButtonsBar mMarkdownButtons;
    private TextView mSelectedMilestoneView;
    private ViewGroup mSelectedAssigneeContainer;
    private TextView mLabelsView;

    private static final String EXTRA_KEY_TITLE = "title";
    private static final String EXTRA_KEY_BODY = "body";
    private static final String EXTRA_KEY_OWNER = "owner";
    private static final String EXTRA_KEY_REPO = "repo";
    private static final String EXTRA_KEY_PROJECT_ID = "project_id";
    private static final String EXTRA_KEY_ISSUE_IID = "issue_iid";
    private static final String EXTRA_KEY_ISSUE_TITLE = "issue_title";
    private static final String EXTRA_KEY_ISSUE_BODY = "issue_body";

    private static final String STATE_KEY_TITLE = "edit_title";
    private static final String STATE_KEY_BODY = "edit_body";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mTitle = savedInstanceState.getString(STATE_KEY_TITLE);
            mBody = savedInstanceState.getString(STATE_KEY_BODY);
        }

        super.onCreate(savedInstanceState);

        if (!Gl4Application.get().isAuthorized()) {
            Intent intent = new Intent(this, GitLabLoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        LayoutInflater headerInflater =
                LayoutInflater.from(new ContextThemeWrapper(this, R.style.HeaderTheme));
        View header = headerInflater.inflate(R.layout.issue_create_header, null);
        addHeaderView(header, false);

        mTitleWrapper = header.findViewById(R.id.title_wrapper);
        mTitleView = header.findViewById(R.id.et_title);
        mTitleView.setOnFocusChangeListener(this);

        mDescView = findViewById(R.id.editor);
        mSelectedMilestoneView = findViewById(R.id.tv_milestone);
        mSelectedAssigneeContainer = findViewById(R.id.assignee_list);
        mLabelsView = findViewById(R.id.tv_labels);

        mMarkdownButtons = findViewById(R.id.markdown_buttons);
        mMarkdownButtons.setEditText(mDescView);

        View topLeftShadow = findViewById(R.id.markdown_buttons_top_left_shadow);
        if (topLeftShadow != null) {
            topLeftShadow.setVisibility(View.GONE);
        }
        View topShadow = findViewById(R.id.markdown_buttons_top_shadow);
        if (topShadow != null) {
            topShadow.setVisibility(View.GONE);
        }

        MarkdownPreviewWebView preview = findViewById(R.id.preview);
        preview.setEditText(mDescView);

        findViewById(R.id.milestone_container).setOnClickListener(this);
        findViewById(R.id.assignee_container).setOnClickListener(this);
        findViewById(R.id.label_container).setOnClickListener(this);

        CoordinatorLayout rootLayout = getRootLayout();
        mFab = (FloatingActionButton)
                getLayoutInflater().inflate(R.layout.accept_fab, rootLayout, false);
        mFab.setOnClickListener(this);
        rootLayout.addView(mFab);

        loadCollaboratorStatus(false);

        if (savedInstanceState == null && !isEditingExistingIssue() && !isContentGivenViaIntent()) {
            loadIssueTemplates();
            mTitleView.setEnabled(false);
            mDescView.setEnabled(false);
            mDescView.setHint(getString(R.string.issue_loading_template_hint));
        }

        mTitleView.setText(mTitle != null ? mTitle : "");
        mDescView.setText(mBody != null ? mBody : "");

        mTitleView.addTextChangedListener(new UiUtils.EmptinessWatchingTextWatcher(mTitleView) {
            @Override
            public void onIsEmpty(boolean isEmpty) {
                if (isEmpty) {
                    mTitleWrapper.setError(getString(R.string.issue_error_title));
                } else {
                    mTitleWrapper.setErrorEnabled(false);
                }
                mFab.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            }
        });

        adjustTabsForHeaderAlignedFab(true);
        setToolbarScrollable(false);
        updateOptionViews();

        addAppBarOffsetListener(this);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return !isEditingExistingIssue()
                ? getString(R.string.issue_create)
                : getString(R.string.issue_edit_title, mIssueIid);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mRepoOwner + "/" + mRepoName;
    }

    @Override
    protected PagerAdapter createAdapter(ViewGroup root) {
        mRootView = root;
        getLayoutInflater().inflate(R.layout.issue_create, root);
        return new EditPagerAdapter();
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mRepoOwner = extras.getString(EXTRA_KEY_OWNER);
        mRepoName = extras.getString(EXTRA_KEY_REPO);
        mProjectId = extras.getLong(EXTRA_KEY_PROJECT_ID, -1L);
        // If mTitle != null here, it was restored from saved state
        if (mTitle == null) {
            if (extras.containsKey(EXTRA_KEY_ISSUE_IID)) {
                mIssueIid = extras.getInt(EXTRA_KEY_ISSUE_IID, -1);
                mTitle = extras.getString(EXTRA_KEY_ISSUE_TITLE);
                mBody = extras.getString(EXTRA_KEY_ISSUE_BODY);
                mOriginalTitle = mTitle;
                mOriginalBody = mBody;
                mOriginalAssignees = new ArrayList<>(mAssignees);
                mOriginalLabels = new ArrayList<>(mLabels);
                mOriginalMilestone = mMilestone;
            } else {
                mTitle = extras.getString(EXTRA_KEY_TITLE, "");
                mBody = extras.getString(EXTRA_KEY_BODY, "");
                mOriginalTitle = "";
                mOriginalBody = "";
                mOriginalAssignees = new ArrayList<>();
                mOriginalLabels = new ArrayList<>();
            }
        }
    }

    @Override
    protected boolean canSwipeToRefresh() {
        // swipe-to-refresh doesn't make much sense in the
        // interaction model of this activity
        return false;
    }

    @Override
    public void onRefresh() {
        mAssigneeSingle = null;
        mLabelSingle = null;
        mMilestoneSingle = null;
        mIsCollaborator = false;
        loadCollaboratorStatus(true);
        super.onRefresh();
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        // Set the bottom padding to make the bottom appear as not moving while the
        // AppBarLayout pushes it down or up.
        mRootView.setPadding(mRootView.getPaddingLeft(), mRootView.getPaddingTop(),
                mRootView.getPaddingRight(), appBarLayout.getTotalScrollRange() + verticalOffset);
    }

    private boolean isEditingExistingIssue() {
        return getIntent().hasExtra(EXTRA_KEY_ISSUE_IID);
    }

    private boolean isContentGivenViaIntent() {
        return getIntent().hasExtra(EXTRA_KEY_TITLE) && getIntent().hasExtra(EXTRA_KEY_BODY);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.milestone_container) {
            showMilestonesDialog();
        } else if (id == R.id.assignee_container) {
            showAssigneesDialog();
        } else if (id == R.id.label_container) {
            showLabelDialog();
        } else if (view instanceof FloatingActionButton) {
            mTitle = mTitleView.getText().toString();
            mBody = mDescView.getText().toString();
            saveIssue();
        }
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (view == mTitleView) {
            mMarkdownButtons.setVisibility(hasFocus ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_KEY_TITLE, mTitleView != null
                ? mTitleView.getText().toString() : mTitle);
        outState.putString(STATE_KEY_BODY, mDescView != null
                ? mDescView.getText().toString() : mBody);
    }

    @Override
    protected Intent navigateUp() {
        if (!isEditingExistingIssue()) {
            // Use the projectId-aware overload so IssueListActivity gets mProjectId.
            return IssueListActivity.makeIntent(this, mRepoOwner, mRepoName, mProjectId);
        }
        // Pass mProjectId so IssueActivity.loadIssue() uses the correct project ID.
        // This is the path that fixes: makeCreateIntent(no-projectId) -> navigateUp -> IssueActivity
        // with mProjectId=-1L causing a 404 on getIssue().
        return IssueActivity.makeIntent(this, mRepoOwner, mRepoName, mProjectId, mIssueIid, null);
    }

    private void showMilestonesDialog() {
        loadMilestones(milestones -> {
            MilestoneEditDialogFragment
                    .newInstance(mMilestone, milestones)
                    .show(getSupportFragmentManager(), "milestoneedit");
        });
    }

    private void showAssigneesDialog() {
        loadPotentialAssignees(assignees -> {
            AssigneeEditDialogFragment
                    .newInstance(mAssignees, assignees)
                    .show(getSupportFragmentManager(), "assigneeedit");
        });
    }

    private void showLabelDialog() {
        loadLabels(labels -> {
            LabelEditDialogFragment
                    .newInstance(mLabels, labels)
                    .show(getSupportFragmentManager(), "labeledit");
        });
    }

    private void updateMilestone(GitLabMilestone newMilestone) {
        mMilestone = newMilestone;
        updateOptionViews();
    }

    private void updateAssignees(List<GitLabUser> newAssignees) {
        mAssignees = newAssignees;
        updateOptionViews();
    }

    private void updateLabels(List<GitLabLabel> newLabels) {
        mLabels = newLabels;
        updateOptionViews();
    }

    private void manageMilestones() {
        Intent intent = IssueMilestoneListActivity.makeIntent(this, mRepoOwner, mRepoName, false);
        mMilestoneManagerLauncher.launch(intent);
    }

    private void manageLabels() {
        Intent intent = IssueLabelListActivity.makeIntent(this, mRepoOwner, mRepoName, false);
        mLabelManagerLauncher.launch(intent);
    }

    private void updateOptionViews() {
        if (mMilestone != null) {
            mSelectedMilestoneView.setText(mMilestone.title());
        } else {
            mSelectedMilestoneView.setText(R.string.issue_clear_milestone);
        }

        LayoutInflater inflater = getLayoutInflater();

        mSelectedAssigneeContainer.removeAllViews();
        if (mAssignees != null && !mAssignees.isEmpty()) {
            for (GitLabUser assignee : mAssignees) {
                View row = inflater.inflate(R.layout.row_assignee, mSelectedAssigneeContainer, false);
                TextView tvAssignee = row.findViewById(R.id.tv_assignee);
                tvAssignee.setText(ApiHelpers.getUserLogin(this, assignee));

                ImageView ivAssignee = row.findViewById(R.id.iv_assignee);
                AvatarHandler.assignAvatar(ivAssignee, assignee);

                mSelectedAssigneeContainer.addView(row);
            }
        } else {
            View row = inflater.inflate(R.layout.row_assignee, mSelectedAssigneeContainer, false);
            TextView tvAssignee = row.findViewById(R.id.tv_assignee);
            tvAssignee.setText(R.string.issue_clear_assignee);
            row.findViewById(R.id.iv_assignee).setVisibility(View.GONE);
            mSelectedAssigneeContainer.addView(row);
        }

        if (mLabels == null || mLabels.isEmpty()) {
            mLabelsView.setText(R.string.issue_no_labels);
        } else {
            mLabelsView.setText(UiUtils.formatLabelList(this, mLabels));
        }
    }

    private void saveIssue() {
        // Always read the latest text from the views so that the TextWatcher's cached
        // value is never stale (fixes the case where FAB is tapped before TextWatcher fires).
        if (mTitleView != null) mTitle = mTitleView.getText().toString();
        if (mDescView != null) mBody = mDescView.getText().toString();

        java.util.Map<String, Object> body = new java.util.HashMap<>();

        if (!ObjectsCompat.equals(mTitle, mOriginalTitle)) {
            body.put("title", mTitle);
        }
        if (!ObjectsCompat.equals(mBody, mOriginalBody)) {
            body.put("description", mBody);
        }
        if (!ObjectsCompat.equals(mMilestone, mOriginalMilestone)) {
            body.put("milestone_id", mMilestone != null ? mMilestone.id : null);
        }
        if (!ObjectsCompat.equals(mAssignees, mOriginalAssignees)) {
            List<Long> assigneeIds = new ArrayList<>();
            for (GitLabUser assignee : mAssignees) {
                assigneeIds.add(assignee.id);
            }
            body.put("assignee_ids", assigneeIds);
        }
        if (!ObjectsCompat.equals(mLabels, mOriginalLabels)) {
            List<String> labelNames = new ArrayList<>();
            for (GitLabLabel label : mLabels) {
                labelNames.add(label.name());
            }
            body.put("labels", String.join(",", labelNames));
        }

        // Always include title for new issues
        if (!isEditingExistingIssue()) {
            body.put("title", mTitle);
            body.put("description", mBody);
        }

        String errorMessage = isEditingExistingIssue()
                ? getString(R.string.issue_error_edit, mIssueIid)
                : getString(R.string.issue_error_create);

        final java.util.Map<String, Object> finalBody = body;
        Single<Long> projectIdSingle = mProjectId > 0
                ? Single.just(mProjectId)
                : SingleFactory.getProjectId(mRepoOwner, mRepoName).doOnSuccess(id -> mProjectId = id);

        final boolean isEdit = isEditingExistingIssue();
        final int issueIid = mIssueIid;
        projectIdSingle
                .flatMap(pid -> {
                    GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
                    Single<Response<GitLabIssue>> single = isEdit
                            ? service.editIssue(pid, issueIid, finalBody)
                            : service.createIssue(pid, finalBody);
                    return single.map(ApiHelpers::throwOnFailure);
                })
                .compose(RxUtils.<GitLabIssue>wrapForBackgroundTask(this, R.string.saving_msg, errorMessage))
                .subscribe(result -> {
                    Intent data = new Intent();
                    data.putExtra("issue_iid", result.iid);
                    // Include project_id so IssueListActivity can open the issue without a 404
                    // when mProjectId was not set at list-launch time.
                    data.putExtra("project_id", result.projectId > 0 ? result.projectId : mProjectId);
                    setResult(RESULT_OK, data);
                    finish();
                }, error -> handleActionFailure("Saving issue failed", error));
    }

    private void loadCollaboratorStatus(boolean force) {
        if (mRepoOwner == null || mRepoName == null) {
            // No owner/repo available (projectId-only path); skip remote check.
            mIsCollaborator = false;
            invalidatePages();
            return;
        }
        SingleFactory.isAppUserRepoCollaborator(mRepoOwner, mRepoName, force)
                .compose(makeLoaderSingle(ID_LOADER_COLLABORATOR_STATUS, force))
                .subscribe(result -> {
                    mIsCollaborator = result;
                    invalidatePages();
                }, this::handleLoadFailure);
    }

    private void loadLabels(OnLabelsLoaded callback) {
        if (mLabelSingle == null) {
            Single<Long> projectIdSingle = mProjectId > 0
                    ? Single.just(mProjectId)
                    : SingleFactory.getProjectId(mRepoOwner, mRepoName).doOnSuccess(id -> mProjectId = id);
            GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
            mLabelSingle = projectIdSingle
                    .flatMap(pid -> service.getLabels(pid, 1, 100)
                            .<List<GitLabLabel>>map(ApiHelpers::throwOnFailure))
                    .compose(RxUtils::doInBackground)
                    .compose(RxUtils.<List<GitLabLabel>>wrapWithProgressDialog(this, R.string.loading_msg))
                    .cache();
        }
        registerTemporarySubscription(
                mLabelSingle.subscribe(result -> callback.handleLoad(result), this::handleLoadFailure));
    }

    private void loadMilestones(OnMilestonesLoaded callback) {
        if (mMilestoneSingle == null) {
            Single<Long> projectIdSingle = mProjectId > 0
                    ? Single.just(mProjectId)
                    : SingleFactory.getProjectId(mRepoOwner, mRepoName).doOnSuccess(id -> mProjectId = id);
            GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
            mMilestoneSingle = projectIdSingle
                    .flatMap(pid -> service.getMilestones(pid, "active", 1, 100)
                            .<List<GitLabMilestone>>map(ApiHelpers::throwOnFailure))
                    .compose(RxUtils::doInBackground)
                    .compose(RxUtils.<List<GitLabMilestone>>wrapWithProgressDialog(this, R.string.loading_msg))
                    .cache();
        }
        registerTemporarySubscription(
                mMilestoneSingle.subscribe(result -> callback.handleLoad(result), this::handleLoadFailure));
    }

    private void loadPotentialAssignees(OnAssigneesLoaded callback) {
        if (mAssigneeSingle == null) {
            Single<Long> projectIdSingle = mProjectId > 0
                    ? Single.just(mProjectId)
                    : SingleFactory.getProjectId(mRepoOwner, mRepoName).doOnSuccess(id -> mProjectId = id);
            GitLabProjectService projectService = ServiceFactory.get(GitLabProjectService.class, false);
            mAssigneeSingle = projectIdSingle
                    .flatMap(pid -> projectService.getAllMembers(pid, 1, 100)
                            .<List<GitLabUser>>map(ApiHelpers::throwOnFailure))
                    .compose(RxUtils::doInBackground)
                    .compose(RxUtils.<List<GitLabUser>>wrapWithProgressDialog(this, R.string.loading_msg))
                    .cache();
        }
        registerTemporarySubscription(
                mAssigneeSingle.subscribe(result -> callback.handleLoad(result), this::handleLoadFailure));
    }

    private void loadIssueTemplates() {
        // GitLab stores issue templates in .gitlab/issue_templates/ (not .github)
        registerTemporarySubscription(getIssueTemplatesSingle(".gitlab/issue_templates")
                .compose(RxUtils.wrapWithProgressDialog(this, R.string.loading_msg))
                .subscribe(result -> {
                    if (result.isPresent() && !result.get().isEmpty()) {
                        List<IssueTemplate> templates = result.get();
                        if (templates.size() == 1) {
                            handleIssueTemplateSelected(templates.get(0));
                        } else {
                            List<IssueTemplate> namedTemplates = templates.stream()
                                    .filter(template -> template.name != null)
                                    .sorted(Comparator.comparing(template -> template.name))
                                    .collect(toList());
                            IssueTemplateSelectionDialogFragment f =
                                    IssueTemplateSelectionDialogFragment.newInstance(namedTemplates);
                            f.show(getSupportFragmentManager(), "template-selection");
                        }
                    } else {
                        handleIssueTemplateSelected(null);
                    }
                }, this::handleLoadFailure));
    }

    private void handleIssueTemplateSelected(IssueTemplate template) {
        mTitleView.setEnabled(true);
        mDescView.setHint(null);
        mDescView.setEnabled(true);
        if (template == null) {
            return;
        }

        if (template.title != null) mTitleView.setText(template.title);
        if (template.content != null) mDescView.setText(template.content);
        if (!template.defaultAssignees.isEmpty()) {
            loadPotentialAssignees(assignees -> {
                final List<GitLabUser> validAssignees = new ArrayList<>();
                for (GitLabUser potentialAssignee : assignees) {
                    if (template.defaultAssignees.contains(potentialAssignee.login())) {
                        validAssignees.add(potentialAssignee);
                    }
                }
                if (!validAssignees.isEmpty()) {
                    mAssignees = validAssignees;
                    updateOptionViews();
                }
            });
        }
        if (!template.defaultLabels.isEmpty()) {
            loadLabels(labels -> {
                final List<GitLabLabel> validLabels = new ArrayList<>();
                for (GitLabLabel label : labels) {
                    if (template.defaultLabels.contains(label.name())) {
                        validLabels.add(label);
                    }
                }
                if (!validLabels.isEmpty()) {
                    mLabels = validLabels;
                    updateOptionViews();
                }
            });
        }
    }

    private Single<Optional<List<IssueTemplate>>> getIssueTemplatesSingle(String path) {
        // GitLab: Use repository tree API to find issue templates
        GitLabRepositoryService service = ServiceFactory.get(GitLabRepositoryService.class, false);
        return service.getTree(mProjectId, path, "HEAD", false, 1, 100)
                .<List<GitLabTreeItem>>map(ApiHelpers::throwOnFailure)
                .map(items -> {
                    for (GitLabTreeItem c : items) {
                        if (c.name != null && c.name.toLowerCase(Locale.US).startsWith("issue_template")) {
                            return Optional.of(c);
                        }
                    }
                    return Optional.<GitLabTreeItem>empty();
                })
                .flatMap(contentOpt -> RxUtils.mapToSingle(contentOpt, content -> {
                    if ("tree".equals(content.type)) {
                        return service.getTree(mProjectId, content.path, "HEAD", false, 1, 100)
                                .<List<GitLabTreeItem>>map(ApiHelpers::throwOnFailure);
                    } else {
                        return Single.just(Collections.singletonList(content));
                    }
                }))
                .map(contentsOpt -> contentsOpt.map(contents -> {
                    List<GitLabTreeItem> files = new ArrayList<>();
                    for (GitLabTreeItem c : contents) {
                        if ("blob".equals(c.type) && c.name != null && c.name.endsWith(".md")) {
                            files.add(c);
                        }
                    }
                    return files;
                }))
                .flatMap(contentsOpt -> RxUtils.mapToSingle(contentsOpt, contents -> {
                    List<Single<IssueTemplate>> result = new ArrayList<>();
                    for (GitLabTreeItem c : contents) {
                        result.add(parseTemplate(service, c));
                    }
                    return Flowable.fromIterable(result)
                            .flatMap(flowable -> flowable.toFlowable())
                            .toList();
                }))
                .compose(upstream -> RxUtils.doInBackground(upstream))
                .compose(RxUtils.<Optional<List<IssueTemplate>>>mapFailureToValue(HttpURLConnection.HTTP_NOT_FOUND, Optional.empty()));
    }

    private Single<IssueTemplate> parseTemplate(GitLabRepositoryService service, GitLabTreeItem content) {
        return service.getRawFile(mProjectId, content.path, "HEAD")
            .map(ApiHelpers::throwOnFailure)
            .map(responseBody -> responseBody.string())
            .map(IssueTemplate::new)
            .compose(RxUtils::doInBackground);
    }

    private static class IssueTemplate implements Parcelable {
        private static final Pattern FRONT_MATTER_PATTERN =
                Pattern.compile("(---\n)(.*?\n)((---)|(\\.\\.\\.))\n?(.*)", Pattern.DOTALL);

        String content;
        String name;
        String description;
        String title;
        final List<String> defaultLabels = new ArrayList<>();
        final List<String> defaultAssignees = new ArrayList<>();

        IssueTemplate(String contentString) {
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(contentString);
            if (matcher.matches()) {
                content = matcher.group(6);
                for (String line : matcher.group(2).split("\n")) {
                    int colonPos = line.indexOf(": ");
                    if (colonPos > 0) {
                        String key = line.substring(0, colonPos);
                        boolean isQuoted = line.charAt(colonPos + 2) == '"'
                                || line.charAt(colonPos + 2) == '\'';
                        String value = isQuoted
                                ? line.substring(colonPos + 3, line.length() - 1)
                                : line.substring(colonPos + 2);
                        switch (key) {
                            case "name": name = value; break;
                            case "about": description = value; break;
                            case "title": title = value; break;
                            case "labels": splitAndFillList(value, defaultLabels); break;
                            case "assignees": splitAndFillList(value, defaultAssignees); break;
                        }
                    }
                }
            } else {
                content = contentString;
            }
        }

        private IssueTemplate(Parcel parcel) {
            content = parcel.readString();
            name = parcel.readString();
            description = parcel.readString();
            title = parcel.readString();
            parcel.readStringList(defaultLabels);
            parcel.readStringList(defaultAssignees);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeString(content);
            parcel.writeString(name);
            parcel.writeString(description);
            parcel.writeString(title);
            parcel.writeStringList(defaultLabels);
            parcel.writeStringList(defaultAssignees);
        }

        public static Parcelable.Creator CREATOR = new Parcelable.Creator<IssueTemplate>() {
            @Override
            public IssueTemplate createFromParcel(Parcel parcel) {
                return new IssueTemplate(parcel);
            }

            @Override
            public IssueTemplate[] newArray(int count) {
                return new IssueTemplate[count];
            }
        };

        private static void splitAndFillList(String input, List<String> list) {
            for (String part : input.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
    }

    public static class IssueTemplateSelectionDialogFragment extends DialogFragment {
        private List<IssueTemplate> mTemplates;

        public static IssueTemplateSelectionDialogFragment newInstance(List<IssueTemplate> templates) {
            IssueTemplateSelectionDialogFragment f = new IssueTemplateSelectionDialogFragment();
            Bundle args = new Bundle();
            args.putParcelableArrayList("templates", new ArrayList<>(templates));
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            mTemplates = requireArguments().getParcelableArrayList("templates");
            var templateItems = mTemplates.stream()
                    .map(template -> new ItemsWithDescriptionAdapter.Item(template.name, template.description))
                    .collect(toList());

            var activity = (IssueEditActivity) requireActivity();
            return new AlertDialog.Builder(activity)
                    .setTitle(R.string.issue_template_dialog_title)
                    .setAdapter(
                        new ItemsWithDescriptionAdapter(activity, templateItems),
                        (dialog, itemIndex) -> activity.handleIssueTemplateSelected(mTemplates.get(itemIndex))
                    )
                    .setNegativeButton(R.string.cancel, (dialog, _btn) -> dialog.cancel())
                    .create();
        }

        @Override
        public void onCancel(@NonNull DialogInterface dialog) {
            super.onCancel(dialog);
            ((IssueEditActivity) requireActivity()).handleIssueTemplateSelected(null);
        }
    }

    private class EditPagerAdapter extends PagerAdapter {
        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            @IdRes int resId = 0;
            switch (position) {
                case 0: resId = R.id.editor_container; break;
                case 1: resId = R.id.preview; break;
                case 2: resId = R.id.options; break;
            }
            return container.findViewById(resId);
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return getString(TITLES[position]);
        }

        @Override
        public int getCount() {
            // Always show all 3 tabs (Write / Preview / Settings).
            // On GitLab any authenticated user who can create an issue can set
            // assignees/labels/milestone, so the Settings tab must always be visible.
            // The collaborator flag can still be used to gate individual fields if needed.
            return TITLES.length;
        }
    }

    public static class MilestoneEditDialogFragment extends DialogFragment {
        // Store milestone ids/titles as strings since GitLabMilestone is not Parcelable
        public static MilestoneEditDialogFragment newInstance(
                GitLabMilestone selected, List<GitLabMilestone> all) {
            MilestoneEditDialogFragment f = new MilestoneEditDialogFragment();
            Bundle args = new Bundle();
            args.putInt("selected_iid", selected != null ? selected.iid : -1);
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<Long> ids = new ArrayList<>();
            ArrayList<Integer> iids = new ArrayList<>();
            for (GitLabMilestone m : all) {
                titles.add(m.title());
                ids.add(m.id);
                iids.add(m.iid);
            }
            args.putStringArrayList("titles", titles);
            args.putLongArray("ids", ids.stream().mapToLong(Long::longValue).toArray());
            args.putIntegerArrayList("iids", iids);
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle args = getArguments();
            ArrayList<String> titles = args.getStringArrayList("titles");
            long[] ids = args.getLongArray("ids");
            ArrayList<Integer> iids = args.getIntegerArrayList("iids");
            int selectedIid = args.getInt("selected_iid", -1);

            final String[] milestones = new String[titles.size() + 1];
            int selected = 0;
            milestones[0] = getString(R.string.issue_clear_milestone);
            for (int i = 0; i < titles.size(); i++) {
                milestones[i + 1] = titles.get(i);
                if (iids.get(i) == selectedIid) selected = i + 1;
            }

            final IssueEditActivity activity = (IssueEditActivity) getContext();
            final DialogInterface.OnClickListener selectCb = (dialog, which) -> {
                if (which == 0) {
                    activity.updateMilestone(null);
                } else {
                    GitLabMilestone m = new GitLabMilestone();
                    m.id = ids[which - 1];
                    m.iid = iids.get(which - 1);
                    m.title = titles.get(which - 1);
                    activity.updateMilestone(m);
                }
                dialog.dismiss();
            };

            return new AlertDialog.Builder(activity)
                    .setCancelable(true)
                    .setTitle(R.string.issue_milestone_hint)
                    .setSingleChoiceItems(milestones, selected, selectCb)
                    .setNegativeButton(R.string.cancel, null)
                    .setNeutralButton(R.string.issue_manage_milestones, (dialog, which) -> {
                        activity.manageMilestones();
                    })
                    .create();
        }
    }

    public static class AssigneeEditDialogFragment extends DialogFragment {
        public static AssigneeEditDialogFragment newInstance(
                List<GitLabUser> selected, List<GitLabUser> allPotentialAssignees) {
            AssigneeEditDialogFragment f = new AssigneeEditDialogFragment();
            Bundle args = new Bundle();
            ArrayList<String> selectedLogins = new ArrayList<>();
            for (GitLabUser u : selected) selectedLogins.add(u.login());
            ArrayList<String> allLogins = new ArrayList<>();
            ArrayList<Long> allIds = new ArrayList<>();
            ArrayList<String> allNames = new ArrayList<>();
            for (GitLabUser u : allPotentialAssignees) {
                allLogins.add(u.login());
                allIds.add(u.id);
                allNames.add(u.name != null ? u.name : u.login());
            }
            args.putStringArrayList("selected_logins", selectedLogins);
            args.putStringArrayList("all_logins", allLogins);
            args.putLongArray("all_ids", allIds.stream().mapToLong(Long::longValue).toArray());
            args.putStringArrayList("all_names", allNames);
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle args = getArguments();
            ArrayList<String> selectedLogins = args.getStringArrayList("selected_logins");
            ArrayList<String> allLogins = args.getStringArrayList("all_logins");
            long[] allIds = args.getLongArray("all_ids");
            ArrayList<String> allNames = args.getStringArrayList("all_names");

            final String[] assigneeNames = allLogins.toArray(new String[0]);
            final boolean[] selection = new boolean[allLogins.size()];
            for (int i = 0; i < allLogins.size(); i++) {
                selection[i] = selectedLogins.contains(allLogins.get(i));
            }

            final IssueEditActivity activity = (IssueEditActivity) getContext();

            DialogInterface.OnMultiChoiceClickListener selectCb =
                    (dialogInterface, which, isChecked) -> selection[which] = isChecked;
            DialogInterface.OnClickListener okCb = (dialog, which) -> {
                List<GitLabUser> newAssigneeList = new ArrayList<>();
                for (int i = 0; i < selection.length; i++) {
                    if (selection[i]) {
                        GitLabUser u = GitLabUser.create(allLogins.get(i), allIds[i]);
                        newAssigneeList.add(u);
                    }
                }
                activity.updateAssignees(newAssigneeList);
                dialog.dismiss();
            };

            return new AlertDialog.Builder(activity)
                    .setCancelable(true)
                    .setTitle(R.string.issue_assignee_hint)
                    .setMultiChoiceItems(assigneeNames, selection, selectCb)
                    .setPositiveButton(R.string.ok, okCb)
                    .setNegativeButton(R.string.cancel, null)
                    .create();
        }
    }

    public static class LabelEditDialogFragment extends DialogFragment {
        public static LabelEditDialogFragment newInstance(
                List<GitLabLabel> selectedLabels, List<GitLabLabel> allLabels) {
            LabelEditDialogFragment f = new LabelEditDialogFragment();
            Bundle args = new Bundle();
            // Store as name+color string arrays
            ArrayList<String> selNames = new ArrayList<>();
            for (GitLabLabel l : selectedLabels) selNames.add(l.name());
            ArrayList<String> allNames = new ArrayList<>();
            ArrayList<String> allColors = new ArrayList<>();
            ArrayList<Long> allIds = new ArrayList<>();
            for (GitLabLabel l : allLabels) {
                allNames.add(l.name());
                allColors.add(l.color != null ? l.color : "#eeeeee");
                allIds.add(l.id);
            }
            args.putStringArrayList("sel_names", selNames);
            args.putStringArrayList("all_names", allNames);
            args.putStringArrayList("all_colors", allColors);
            args.putLongArray("all_ids", allIds.stream().mapToLong(Long::longValue).toArray());
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            LayoutInflater inflater = getLayoutInflater();
            Bundle args = getArguments();
            ArrayList<String> selNames = args.getStringArrayList("sel_names");
            ArrayList<String> allNames = args.getStringArrayList("all_names");
            ArrayList<String> allColors = args.getStringArrayList("all_colors");
            long[] allIds = args.getLongArray("all_ids");

            final List<String> selectedNames = new ArrayList<>(selNames);
            View labelContainerView = inflater.inflate(R.layout.generic_linear_container, null);
            ViewGroup container = labelContainerView.findViewById(R.id.container);

            View.OnClickListener clickListener = view -> {
                String name = (String) view.getTag();
                if (selectedNames.contains(name)) {
                    selectedNames.remove(name);
                    setLabelSelection((TextView) view, false, 0);
                } else {
                    selectedNames.add(name);
                    int idx = allNames.indexOf(name);
                    int color = android.graphics.Color.parseColor(allColors.get(idx));
                    setLabelSelection((TextView) view, true, color);
                }
            };

            for (int i = 0; i < allNames.size(); i++) {
                String name = allNames.get(i);
                String colorStr = allColors.get(i);
                int color = android.graphics.Color.parseColor(colorStr);

                final View rowView = inflater.inflate(R.layout.row_issue_create_label, container, false);
                View viewColor = rowView.findViewById(R.id.view_color);
                viewColor.setBackgroundColor(color);

                final TextView tvLabel = rowView.findViewById(R.id.tv_title);
                tvLabel.setText(EmojiParser.parseToUnicode(name));
                tvLabel.setOnClickListener(clickListener);
                tvLabel.setTag(name);

                setLabelSelection(tvLabel, selectedNames.contains(name), color);
                container.addView(rowView);
            }

            IssueEditActivity activity = (IssueEditActivity) getContext();
            return new AlertDialog.Builder(activity)
                    .setCancelable(true)
                    .setTitle(R.string.issue_labels)
                    .setView(labelContainerView)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        // Reconstruct GitLabLabel list from selected names
                        List<GitLabLabel> result = new ArrayList<>();
                        for (int i = 0; i < allNames.size(); i++) {
                            if (selectedNames.contains(allNames.get(i))) {
                                GitLabLabel l = new GitLabLabel();
                                l.id = allIds[i];
                                l.name = allNames.get(i);
                                l.color = allColors.get(i);
                                result.add(l);
                            }
                        }
                        activity.updateLabels(result);
                    })
                    .setNeutralButton(R.string.issue_manage, (dialog, which) -> {
                        activity.manageLabels();
                    })
                    .create();
        }

        private void setLabelSelection(TextView view, boolean selected, int color) {
            if (selected) {
                view.setTypeface(view.getTypeface(), Typeface.BOLD);
                view.setBackgroundColor(color);
                view.setTextColor(UiUtils.textColorForBackground(getContext(), color));
            } else {
                view.setTypeface(view.getTypeface(), Typeface.NORMAL);
                view.setBackgroundColor(0);
                view.setTextColor(ContextCompat.getColor(getContext(), R.color.label_fg));
            }
        }
    }
}
