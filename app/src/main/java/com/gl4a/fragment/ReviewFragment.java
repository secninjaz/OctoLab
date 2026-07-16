package com.gl4a.fragment;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.timeline.TimelineItemAdapter;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabReaction;
import com.gl4a.gitlab.model.GitLabReview;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.IntentUtils;
import com.gl4a.widget.EditorBottomSheet;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;

/**
 * Stub fragment for GitLab merge request review display.
 *
 * The original implementation relied on GitHub SDK types (PullRequestReviewService,
 * PullRequestReviewCommentService, CreateReviewComment) which are not available.
 * Full GitLab review display is pending native implementation.
 */
public class ReviewFragment extends ListDataBaseFragment<TimelineItem> implements
        TimelineItemAdapter.OnCommentAction, ConfirmationDialogFragment.Callback,
        EditorBottomSheet.Callback, EditorBottomSheet.Listener {

    public static ReviewFragment newInstance(String repoOwner, String repoName, int issueNumber,
            GitLabReview review, IntentUtils.InitialCommentMarker initialComment) {
        ReviewFragment f = new ReviewFragment();
        Bundle args = new Bundle();
        args.putString("repo_owner", repoOwner);
        args.putString("repo_name", repoName);
        args.putInt("issue_number", issueNumber);
        args.putParcelable("review", review);
        args.putParcelable("initial_comment", initialComment);
        f.setArguments(args);
        return f;
    }

    private EditorBottomSheet mBottomSheet;
    private String mRepoOwner;
    private String mRepoName;
    private int mIssueNumber;
    private GitLabReview mReview;
    private long mSelectedReplyCommentId;
    private @StringRes int mCommentEditorHintResId = R.string.review_reply_hint;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mRepoOwner = args.getString("repo_owner");
        mRepoName = args.getString("repo_name");
        mIssueNumber = args.getInt("issue_number");
        mReview = args.getParcelable("review");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View listContent = super.onCreateView(inflater, container, savedInstanceState);
        View v = inflater.inflate(R.layout.comment_list, container, false);
        FrameLayout listContainer = v.findViewById(R.id.list_container);
        listContainer.addView(listContent);
        mBottomSheet = v.findViewById(R.id.bottom_sheet);
        mBottomSheet.setCallback(this);
        mBottomSheet.setResizingView(listContainer);
        mBottomSheet.setListener(this);
        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        getBaseActivity().addAppBarOffsetListener(mBottomSheet);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        getBaseActivity().removeAppBarOffsetListener(mBottomSheet);
    }

    @Override
    protected Single<List<TimelineItem>> onCreateDataSingle(boolean bypassCache) {
        // TODO: Implement GitLab merge request review discussion loading
        return Single.just(new ArrayList<>());
    }

    @Override
    protected RootAdapter<TimelineItem, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new TimelineItemAdapter(getActivity(), mRepoOwner, mRepoName, mIssueNumber,
                true, false, this);
    }

    @Override
    protected int getEmptyTextResId() {
        return 0;
    }

    @Override
    public void editComment(GitLabComment comment) { }

    @Override
    public void deleteComment(final GitLabComment comment) { }

    @Override
    public void onConfirmed(String tag, Parcelable data) { }

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
    public void quoteText(CharSequence text) {
        mBottomSheet.addQuote(text);
    }

    @Override
    public void onReplyCommentSelected(long replyToId) {
        mSelectedReplyCommentId = replyToId;
        mBottomSheet.setLocked(false, 0);
    }

    @Override
    public long getSelectedReplyCommentId() {
        return mSelectedReplyCommentId;
    }

    @Override
    public String getShareSubject(GitLabComment comment) {
        return null;
    }

    @Override
    public void addText(CharSequence text) { }

    @Override
    public Single<List<GitLabReaction>> loadReactionDetails(final GitLabComment comment, boolean bypassCache) {
        return Single.just(new ArrayList<>());
    }

    @Override
    public Single<GitLabReaction> addReaction(GitLabComment comment, String content) {
        return Single.error(new UnsupportedOperationException("Reactions not yet implemented for GitLab"));
    }

    @Override
    public Single<Boolean> deleteReaction(GitLabComment comment, long reactionId) {
        return Single.just(false);
    }

    @Override
    public int getCommentEditorHintResId() {
        return mCommentEditorHintResId;
    }

    @Override
    public int getEditorErrorMessageResId() {
        return R.string.issue_error_comment;
    }

    @Override
    public Single<?> onEditorDoSend(String comment) {
        // TODO: Implement GitLab review comment submission
        return Single.just(comment);
    }

    @Override
    public void onEditorTextSent() {
        onRefresh();
    }

    @Override
    public CoordinatorLayout getRootLayout() {
        return getBaseActivity().getRootLayout();
    }

    @Override
    public boolean canChildScrollUp() {
        return (mBottomSheet != null && mBottomSheet.isExpanded()) || super.canChildScrollUp();
    }

    @Override
    public boolean onBackPressed() {
        if (mBottomSheet != null && mBottomSheet.isInAdvancedMode()) {
            mBottomSheet.setAdvancedMode(false);
            return true;
        }
        return false;
    }
}
