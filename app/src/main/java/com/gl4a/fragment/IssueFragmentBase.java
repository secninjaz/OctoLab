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
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabAwardEmoji;
import com.gl4a.gitlab.service.GitLabAwardEmojiService;
import java.util.HashMap;
import java.util.Map;
import com.gl4a.gitlab.model.GitLabReactions;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabLabel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.UserActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.timeline.TimelineItemAdapter;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.HttpImageGetter;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.EditorBottomSheet;
import com.gl4a.widget.ReactionBar;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.RecyclerView;
import io.reactivex.Single;
import retrofit2.Response;
import com.gl4a.gitlab.model.GitLabIssueEventType;

public abstract class IssueFragmentBase extends ListDataBaseFragment<TimelineItem> implements
        View.OnClickListener, TimelineItemAdapter.OnCommentAction,
        ConfirmationDialogFragment.Callback,
        EditorBottomSheet.Callback, EditorBottomSheet.Listener,
        ReactionBar.Callback, ReactionBar.Item, ReactionBar.ReactionDetailsCache.Listener {
    protected static final List<String> INTERESTING_EVENTS = Arrays.asList(
            GitLabIssueEventType.Closed, GitLabIssueEventType.Reopened, GitLabIssueEventType.Merged,
            GitLabIssueEventType.Referenced, GitLabIssueEventType.Assigned, GitLabIssueEventType.Unassigned,
            GitLabIssueEventType.Labeled, GitLabIssueEventType.Unlabeled, GitLabIssueEventType.Locked,
            GitLabIssueEventType.Unlocked, GitLabIssueEventType.Milestoned, GitLabIssueEventType.Demilestoned,
            GitLabIssueEventType.Renamed, GitLabIssueEventType.HeadRefDeleted, GitLabIssueEventType.HeadRefRestored,
            GitLabIssueEventType.HeadRefForcePushed, GitLabIssueEventType.AutoMergeDisabled,
            GitLabIssueEventType.AutoMergeEnabled, GitLabIssueEventType.AutoRebaseEnabled,
            GitLabIssueEventType.AutoSquashEnabled, GitLabIssueEventType.AddedToMergeQueue,
            GitLabIssueEventType.RemovedFromMergeQueue, GitLabIssueEventType.CommentDeleted,
            GitLabIssueEventType.ReviewRequested, GitLabIssueEventType.ReviewRequestRemoved,
            GitLabIssueEventType.ConvertToDraft, GitLabIssueEventType.ReadyForReview,
            GitLabIssueEventType.ReviewDismissed, GitLabIssueEventType.CrossReferenced,
            GitLabIssueEventType.Transferred, GitLabIssueEventType.Commented
    );

    protected View mListHeaderView;
    protected GitLabIssue mIssue;
    protected String mRepoOwner;
    protected String mRepoName;
    private IntentUtils.InitialCommentMarker mInitialComment;
    private boolean mIsCollaborator;
    private boolean mListShown;
    private ReactionBar.AddReactionMenuHelper mReactionMenuHelper;
    private final ReactionBar.ReactionDetailsCache mReactionDetailsCache =
            new ReactionBar.ReactionDetailsCache(this);
    private TimelineItemAdapter mAdapter;
    private HttpImageGetter mImageGetter;
    private EditorBottomSheet mBottomSheet;

    protected final ActivityResultLauncher<Intent> mEditLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                reloadEvents(true);
                getActivity().setResult(Activity.RESULT_OK);
            }));

    protected static Bundle buildArgs(String repoOwner, String repoName,
            GitLabIssue issue, boolean isCollaborator, IntentUtils.InitialCommentMarker initialComment) {
        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putParcelable("issue", issue);
        args.putBoolean("collaborator", isCollaborator);
        args.putParcelable("initial_comment", initialComment);
        return args;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mRepoOwner = args.getString("owner");
        mRepoName = args.getString("repo");
        mIssue = args.getParcelable("issue");
        mIsCollaborator = args.getBoolean("collaborator");
        if (!android.text.TextUtils.isEmpty(mRepoOwner) && !android.text.TextUtils.isEmpty(mRepoName)) {
            Gl4Application.get().setCurrentProjectPath(mRepoOwner + "/" + mRepoName);
        }
        mInitialComment = args.getParcelable("initial_comment");
        args.remove("initial_comment");

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View listContent = super.onCreateView(inflater, container, savedInstanceState);
        View v = inflater.inflate(R.layout.comment_list, container, false);

        FrameLayout listContainer = v.findViewById(R.id.list_container);
        listContainer.addView(listContent);

        mBottomSheet = v.findViewById(R.id.bottom_sheet);
        mBottomSheet.setCallback(this);
        mBottomSheet.setResizingView(listContainer);
        mBottomSheet.setListener(this);

        mImageGetter = new HttpImageGetter(inflater.getContext());
        updateCommentSectionVisibility(v);
        updateCommentLockState();

        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mInitialComment == null) {
            // We want to make the user able to read the issue/PR while the rest of the conversation is still loading,
            // but at the same time we want to avoid item pop-in when the conversation loads quickly
            View contentContainer = view.findViewById(R.id.content_container);
            contentContainer.postDelayed(
                    () -> UiUtils.updateViewVisibility(contentContainer, isResumed(), true),
                    800);
        }

        BaseActivity activity = getBaseActivity();
        activity.addAppBarOffsetListener(mBottomSheet);
        mBottomSheet.post(() -> {
            // Fix an issue where the bottom sheet is initially located outside of the visible screen area
            mBottomSheet.resetPeekHeight(activity.getAppBarTotalScrollRange());
        });

        fillData();
        fillLabels(mIssue.labels());
        updateCommentLockState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mReactionDetailsCache.destroy();
        mImageGetter.destroy();
        mImageGetter = null;
        if (mAdapter != null) {
            mAdapter.destroy();
            mAdapter = null;
        }

        getBaseActivity().removeAppBarOffsetListener(mBottomSheet);
    }

    @Override
    protected void onRecyclerViewInflated(RecyclerView view, LayoutInflater inflater) {
        super.onRecyclerViewInflated(view, inflater);

        mListHeaderView = inflater.inflate(R.layout.issue_comment_list_header, view, false);
        mAdapter.setHeaderView(mListHeaderView);
        View loadingView = inflater.inflate(R.layout.list_loading_view, view, false);
        showLoadingIndicator(loadingView);
    }

    @Override
    public boolean onBackPressed() {
        if (mBottomSheet != null && mBottomSheet.isInAdvancedMode()) {
            mBottomSheet.setAdvancedMode(false);
            return true;
        }
        return false;
    }

    @Override
    public void onRefresh() {
        if (mListHeaderView != null) {
            getActivity().invalidateOptionsMenu();
            fillLabels(null);
        }
        if (mImageGetter != null) {
            mImageGetter.clearHtmlCache();
        }
        mReactionDetailsCache.clear();
        super.onRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        mImageGetter.resume();
        mAdapter.resume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mImageGetter.pause();
        mAdapter.pause();
    }

    @Override
    public boolean canChildScrollUp() {
        return (mBottomSheet != null && mBottomSheet.isExpanded()) || super.canChildScrollUp();
    }

    @Override
    public CoordinatorLayout getRootLayout() {
        return getBaseActivity().getRootLayout();
    }

    @Override
    protected void setHighlightColors(int colorAttrId, int statusBarColorAttrId) {
        super.setHighlightColors(colorAttrId, statusBarColorAttrId);
        mBottomSheet.setHighlightColor(colorAttrId);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.issue_fragment_menu, menu);

        MenuItem reactItem = menu.findItem(R.id.react);
        if (!Gl4Application.get().isAuthorized() || isLocked()) {
            reactItem.setVisible(false);
        } else {
            inflater.inflate(R.menu.reaction_menu, reactItem.getSubMenu());
            mReactionMenuHelper = new ReactionBar.AddReactionMenuHelper(getActivity(),
                    reactItem.getSubMenu(), this, this, mReactionDetailsCache);
            mReactionMenuHelper.startLoadingIfNeeded();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mReactionMenuHelper != null && mReactionMenuHelper.onItemClick(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void reloadEvents(boolean alsoClearCaches) {
        if (mAdapter != null && !alsoClearCaches) {
            // Don't clear adapter's cache, we're only interested in the new event
            mAdapter.suppressCacheClearOnNextClear();
        }
        super.onRefresh();
    }

    @Override
    protected RootAdapter<TimelineItem, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        mAdapter = new TimelineItemAdapter(getActivity(), mRepoOwner, mRepoName, mIssue.number(),
                mIssue.pullRequest() != null, true, this);
        mAdapter.setLocked(isLocked());
        return mAdapter;
    }

    @Override
    protected void onAddData(RootAdapter<TimelineItem, ?> adapter, List<TimelineItem> data) {
        super.onAddData(adapter, data);
        if (mInitialComment != null) {
            for (int i = 0; i < data.size(); i++) {
                TimelineItem item = data.get(i);
                long itemId = 0;
                if (item instanceof TimelineItem.TimelineComment) {
                    itemId = ((TimelineItem.TimelineComment) item).comment().id();
                } else if (item instanceof TimelineItem.TimelineReview) {
                    itemId = ((TimelineItem.TimelineReview) item).review().id();
                }
                if (mInitialComment.matches(itemId, item.getCreatedAt())) {
                    scrollToAndHighlightPosition(i + 1 /* adjust for header view */);
                    break;
                }
            }
            mInitialComment = null;
        }

        updateMentionUsers();
        removeLoadingIndicator(adapter);
    }

    private void showLoadingIndicator(View loadingView) {
        loadingView.setVisibility(View.VISIBLE);
        mAdapter.setFooterView(loadingView, null);
    }

    private void removeLoadingIndicator(RootAdapter<TimelineItem, ?> adapter) {
        adapter.setFooterView(null, null);
    }

    @Override
    protected int getEmptyTextResId() {
        return 0;
    }

    @Override
    protected void updateEmptyState() {
        // we're never empty -> don't call super
    }

    @Override
    protected void setContentShown(boolean shown) {
        super.setContentShown(shown);
        mListShown = shown;
        updateCommentSectionVisibility(getView());
    }

    private void updateCommentSectionVisibility(View v) {
        if (v == null) {
            return;
        }

        int commentVisibility = mListShown && Gl4Application.get().isAuthorized()
                ? View.VISIBLE : View.GONE;
        mBottomSheet.setVisibility(commentVisibility);
    }

    private boolean isLocked() {
        return mIssue.locked() && !mIsCollaborator;
    }

    private void updateMentionUsers() {
        Set<GitLabUser> users = mAdapter.getUsers();
        if (mIssue.user() != null) {
            users.add(mIssue.user());
        }
        mBottomSheet.setMentionUsers(users);
    }

    private void updateCommentLockState() {
        mBottomSheet.setLocked(isLocked(), R.string.comment_editor_locked_hint);
    }

    private void fillData() {
        ImageView ivGravatar = mListHeaderView.findViewById(R.id.iv_gravatar);
        AvatarHandler.assignAvatar(ivGravatar, mIssue.user());
        ivGravatar.setTag(mIssue.user());
        ivGravatar.setOnClickListener(this);

        TextView tvExtra = mListHeaderView.findViewById(R.id.tv_extra);
        tvExtra.setText(ApiHelpers.getUserLoginWithType(getActivity(), mIssue.user()));
        tvExtra.setOnClickListener(this);
        tvExtra.setTag(mIssue.user());

        TextView tvTimestamp = mListHeaderView.findViewById(R.id.tv_timestamp);
        tvTimestamp.setText(StringUtils.formatRelativeTime(getActivity(),
                mIssue.createdAt(), true));

        String body = mIssue.body();
        TextView descriptionView = mListHeaderView.findViewById(R.id.tv_desc);
        if (!StringUtils.isBlank(body)) {
            mImageGetter.bindMarkdown(descriptionView, body, mIssue.id());

            if (!isLocked()) {
                descriptionView.setCustomSelectionActionModeCallback(
                        new UiUtils.QuoteActionModeCallback(descriptionView) {
                    @Override
                    public void onTextQuoted(CharSequence text) {
                        quoteText(text);
                    }
                });
            } else {
                descriptionView.setCustomSelectionActionModeCallback(null);
            }
        } else {
            SpannableString noDescriptionString = new SpannableString(getString(R.string.issue_no_description));
            noDescriptionString.setSpan(new StyleSpan(Typeface.ITALIC), 0, noDescriptionString.length(), 0);
            descriptionView.setText(noDescriptionString);
        }

        View milestoneGroup = mListHeaderView.findViewById(R.id.milestone_container);
        if (mIssue.milestone() != null) {
            TextView tvMilestone = mListHeaderView.findViewById(R.id.tv_milestone);
            tvMilestone.setText(mIssue.milestone().title());
            milestoneGroup.setVisibility(View.VISIBLE);
        } else {
            milestoneGroup.setVisibility(View.GONE);
        }

        View assigneeGroup = mListHeaderView.findViewById(R.id.assignee_container);
        List<GitLabUser> assignees = mIssue.assignees();
        if (assignees != null && !assignees.isEmpty()) {
            ViewGroup assigneeContainer = mListHeaderView.findViewById(R.id.assignee_list);
            LayoutInflater inflater = getLayoutInflater();
            assigneeContainer.removeAllViews();
            for (GitLabUser assignee : assignees) {
                View row = inflater.inflate(R.layout.row_assignee, assigneeContainer, false);
                TextView tvAssignee = row.findViewById(R.id.tv_assignee);
                tvAssignee.setText(ApiHelpers.getUserLogin(getActivity(), assignee));

                ImageView ivAssignee = row.findViewById(R.id.iv_assignee);
                AvatarHandler.assignAvatar(ivAssignee, assignee);
                ivAssignee.setTag(assignee);
                ivAssignee.setOnClickListener(this);

                assigneeContainer.addView(row);
            }
            assigneeGroup.setVisibility(View.VISIBLE);
        } else {
            assigneeGroup.setVisibility(View.GONE);
        }

        ReactionBar reactions = mListHeaderView.findViewById(R.id.reactions);
        reactions.setCallback(this, this);
        reactions.setDetailsCache(mReactionDetailsCache);
        // GitLabIssue.reactions() always returns null — fetch award emojis from API instead
        loadIssueBodyReactions(reactions);

        assignHighlightColor();
        bindSpecialViews(mListHeaderView);
    }

    private void loadIssueBodyReactions(ReactionBar bar) {
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, false);
        boolean isMR = mIssue.pullRequest() != null;
        Single<Response<List<GitLabAwardEmoji>>> request = isMR
                ? service.getMergeRequestAwardEmojis(mIssue.projectId, mIssue.iid, 1, 100)
                : service.getIssueAwardEmojis(mIssue.projectId, mIssue.iid, 1, 100);
        request.map(ApiHelpers::throwOnFailure)
                .compose(RxUtils::doInBackground)
                .subscribe(emojis -> {
                    Map<String, Integer> counts = new HashMap<>();
                    for (GitLabAwardEmoji e : emojis) {
                        counts.merge(e.name, 1, Integer::sum);
                    }
                    GitLabReactions r = GitLabReactions.builder()
                            .plusOne(counts.getOrDefault("thumbsup", 0))
                            .minusOne(counts.getOrDefault("thumbsdown", 0))
                            .laugh(counts.getOrDefault("laughing", 0))
                            .hooray(counts.getOrDefault("tada", 0))
                            .heart(counts.getOrDefault("heart", 0))
                            .confused(counts.getOrDefault("confused", 0))
                            .rocket(counts.getOrDefault("rocket", 0))
                            .eyes(counts.getOrDefault("eyes", 0))
                            .build();
                    bar.setReactions(r);
                }, error -> { /* non-fatal — leave bar hidden */ });
    }

    private void fillLabels(List<GitLabLabel> labels) {
        View labelGroup = mListHeaderView.findViewById(R.id.label_container);
        if (labels != null && !labels.isEmpty()) {
            TextView labelView = mListHeaderView.findViewById(R.id.labels);
            labelView.setText(UiUtils.formatLabelList(getActivity(), labels));
            labelGroup.setVisibility(View.VISIBLE);
        } else {
            labelGroup.setVisibility(View.GONE);
        }
    }

    @Override
    public Object getCacheKey() {
        return mIssue.id();
    }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(ReactionBar.Item item, boolean bypassCache) {
        // Load award emojis for the issue/MR body itself (shown in the reaction popup)
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, bypassCache);
        boolean isMR = mIssue.pullRequest() != null;
        Single<Response<List<GitLabAwardEmoji>>> request = isMR
                ? service.getMergeRequestAwardEmojis(mIssue.projectId, mIssue.iid, 1, 100)
                : service.getIssueAwardEmojis(mIssue.projectId, mIssue.iid, 1, 100);
        return request.map(response -> {
            List<GitLabReaction> result = new java.util.ArrayList<>();
            if (response.isSuccessful() && response.body() != null) {
                for (GitLabAwardEmoji e : response.body()) {
                    GitLabReaction r = new GitLabReaction();
                    r.id = e.id; r.name = e.name; r.user = e.user;
                    result.add(r);
                }
            }
            return result;
        }).compose(RxUtils::doInBackground);
    }

    @Override
    public boolean canAddReaction() {
        return !isLocked();
    }

    @Override
    public Single<GitLabReaction> addReaction(ReactionBar.Item item, String content) {
        // Add award emoji on the issue itself
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, false);
        HashMap<String, Object> body = new HashMap<>();
        // Map reaction content to GitLab emoji name
        body.put("name", mapContentToEmojiName(content));
        return service.addIssueAwardEmoji(mIssue.projectId, mIssue.iid, body)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new RuntimeException("Failed to add reaction: HTTP " + response.code());
                    }
                    GitLabAwardEmoji emoji = response.body();
                    GitLabReaction r = new GitLabReaction();
                    r.id = emoji.id;
                    r.name = emoji.name;
                    r.user = emoji.user;
                    return r;
                })
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<Boolean> deleteReaction(ReactionBar.Item item, long reactionId) {
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, false);
        return service.deleteIssueAwardEmoji(mIssue.projectId, mIssue.iid, reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(final GitLabComment comment,
            boolean bypassCache) {
        // Load award emojis on a note (comment)
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, bypassCache);
        return service.getIssueNoteAwardEmojis(mIssue.projectId, mIssue.iid, comment.id(), 1, 50)
                .map(response -> {
                    java.util.List<GitLabReaction> result = new java.util.ArrayList<>();
                    if (response.isSuccessful() && response.body() != null) {
                        for (GitLabAwardEmoji e : response.body()) {
                            GitLabReaction r = new GitLabReaction();
                            r.id = e.id; r.name = e.name; r.user = e.user;
                            result.add(r);
                        }
                    }
                    return result;
                })
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<GitLabReaction> addReaction(GitLabComment comment, String content) {
        // Add award emoji on a comment/note
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, false);
        HashMap<String, Object> body = new HashMap<>();
        body.put("name", mapContentToEmojiName(content));
        return service.addIssueNoteAwardEmoji(mIssue.projectId, mIssue.iid, comment.id(), body)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new RuntimeException("Failed to add reaction: HTTP " + response.code());
                    }
                    GitLabAwardEmoji emoji = response.body();
                    GitLabReaction r = new GitLabReaction();
                    r.id = emoji.id; r.name = emoji.name; r.user = emoji.user;
                    return r;
                })
                .compose(RxUtils::doInBackground);
    }

    @Override
    public Single<Boolean> deleteReaction(GitLabComment comment, long reactionId) {
        GitLabAwardEmojiService service = ServiceFactory.get(GitLabAwardEmojiService.class, false);
        return service.deleteIssueNoteAwardEmoji(mIssue.projectId, mIssue.iid, comment.id(), reactionId)
                .map(r -> r.isSuccessful() || r.code() == 404)
                .compose(RxUtils::doInBackground);
    }

    protected static String mapContentToEmojiName(String content) {
        if (content == null) return "thumbsup";
        switch (content) {
            case "+1":      return "thumbsup";
            case "-1":      return "thumbsdown";
            case "laugh":   return "laughing";
            case "hooray":  return "tada";
            case "heart":   return "heart";
            case "confused":return "confused";
            case "rocket":  return "rocket";
            case "eyes":    return "eyes";
            default:        return content;
        }
    }

    @Override
    public void onReactionsUpdated(ReactionBar.Item item, GitLabReactions reactions) {
        // GitLab reactions: refresh issue instead
        loadIssue(true);
        if (mListHeaderView != null) {
            ReactionBar bar = mListHeaderView.findViewById(R.id.reactions);
            bar.setReactions(reactions);
        }
        if (mReactionMenuHelper != null) {
            mReactionMenuHelper.updateMenuItems();
            getActivity().invalidateOptionsMenu();
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.tv_extra) {
            GitLabUser user = (GitLabUser) v.getTag();
            addText(StringUtils.formatMention(getContext(), user));
            return;
        }
        Intent intent = UserActivity.makeIntent(getActivity(), (GitLabUser) v.getTag());
        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    public void quoteText(CharSequence text) {
        mBottomSheet.addQuote(text);
    }

    @Override
    public void addText(CharSequence text) {
        mBottomSheet.addText(text);
    }

    @Override
    public Single<?> onEditorDoSend(String comment) {
        GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, false);
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("body", comment);
        return service.createComment(mIssue.projectId, mIssue.number(), request)
                .map(ApiHelpers::throwOnFailure);
    }

    @Override
    public void onEditorTextSent() {
        // reload comments
        if (isAdded()) {
            reloadEvents(false);
        }
        getActivity().setResult(Activity.RESULT_OK);
    }

    @Override
    public int getEditorErrorMessageResId() {
        return R.string.issue_error_comment;
    }

    @Override
    public void deleteComment(final GitLabComment comment) {
        ConfirmationDialogFragment.show(this, R.string.delete_comment_message,
                R.string.delete, comment, "deleteconfirm");
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        GitLabComment comment = (GitLabComment) data;
        handleDeleteComment(comment);
    }

    @Override
    public String getShareSubject(GitLabComment comment) {
        return getString(R.string.share_comment_subject, comment.id(), mIssue.number(),
                mRepoOwner + "/" + mRepoName);
    }

    @Override
    public void onToggleAdvancedMode(boolean advancedMode) {
        getBaseActivity().collapseAppBar();
        getBaseActivity().setAppBarLocked(advancedMode);
        mBottomSheet.resetPeekHeight(0);
    }

    @Override
    public void onScrollingInBasicEditor(boolean scrolling) {
        getBaseActivity().setAppBarLocked(scrolling);
    }

    @Override
    public void onReplyCommentSelected(long replyToId) {
        // Not used in this screen
    }

    @Override
    public long getSelectedReplyCommentId() {
        // Not used in this screen
        return 0;
    }

    /** Override in subclass to reload the parent issue/PR object. */
    protected void loadIssue(boolean force) {
        // no-op by default; subclasses may override to refresh the issue header
    }

    protected abstract void bindSpecialViews(View headerView);
    protected abstract void assignHighlightColor();
    protected abstract Single<Response<Void>> doDeleteComment(GitLabComment comment);

    private void handleDeleteComment(GitLabComment comment) {
        doDeleteComment(comment)
                .map(ApiHelpers::mapToBooleanOrThrowOnFailure)
                .compose(RxUtils.wrapForBackgroundTask(getBaseActivity(),
                        R.string.deleting_msg, R.string.error_delete_comment))
                .subscribe(result -> {
                    reloadEvents(false);
                    getActivity().setResult(Activity.RESULT_OK);
                }, error -> handleActionFailure("Deleting comment failed", error));
    }
}
