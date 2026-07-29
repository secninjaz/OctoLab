package com.gl4a.fragment;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.gl4a.BaseActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.EditCommitCommentActivity;
import com.gl4a.adapter.CommitCommentAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.widget.EditorBottomSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.reactivex.Single;

import static java.util.stream.Collectors.toCollection;

public class CommitCommentsFragment extends ListDataBaseFragment<GitLabComment> implements
        CommitCommentAdapter.OnCommentAction, ConfirmationDialogFragment.Callback,
        EditorBottomSheet.Callback, EditorBottomSheet.Listener {

    public static CommitCommentsFragment newInstance(String repoOwner, String repoName, String commitSha, GitLabCommit commit,
            List<GitLabComment> allComments, IntentUtils.InitialCommentMarker initialComment) {
        CommitCommentsFragment f = new CommitCommentsFragment();

        ArrayList<GitLabComment> nonPositionalComments = allComments.stream()
                .filter(comment -> comment.position() == null)
                .collect(toCollection(ArrayList::new));

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putString("sha", commitSha);
        args.putParcelable("commit_author", commit.author());
        args.putParcelable("committer", (android.os.Parcelable) null);
        args.putParcelable("initial_comment", initialComment);
        // Commits can potentially have a very high number of comments.
        // In order to avoid TransactionTooLargeExceptions being thrown when the activity we're
        // attached to is stopped, store them in compressed form.
        IntentUtils.putCompressedValueToBundle(args, "comments", nonPositionalComments);
        f.setArguments(args);
        return f;
    }

    public interface CommentUpdateListener {
        void onCommentsUpdated();
    }

    private String mRepoOwner;
    private String mRepoName;
    private String mObjectSha;
    private GitLabUser mCommitAuthor;
    private GitLabUser mCommitter;
    private IntentUtils.InitialCommentMarker mInitialComment;

    private CommitCommentAdapter mAdapter;
    private EditorBottomSheet mBottomSheet;

    private final ActivityResultLauncher<Intent> mEditLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> refreshComments())
    );

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mRepoOwner = args.getString("owner");
        mRepoName = args.getString("repo");
        mObjectSha = args.getString("sha");
        mCommitAuthor = args.getParcelable("commit_author");
        mCommitter = args.getParcelable("committer");
        mInitialComment = args.getParcelable("initial_comment");
        args.remove("initial_comment");
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

        if (!Gl4Application.get().isAuthorized()) {
            mBottomSheet.setVisibility(View.GONE);
        }

        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        getBaseActivity().addAppBarOffsetListener(mBottomSheet);
        mBottomSheet.post(() -> {
            // Fix an issue where the bottom sheet is initially located outside of the visible
            // screen area
            final BaseActivity activity = getBaseActivity();
            if (activity != null) {
                mBottomSheet.resetPeekHeight(activity.getAppBarTotalScrollRange());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mAdapter != null) {
            mAdapter.destroy();
            mAdapter = null;
        }

        getBaseActivity().removeAppBarOffsetListener(mBottomSheet);
    }

    @Override
    public void onResume() {
        super.onResume();
        mAdapter.resume();
    }

    @Override
    public void onPause() {
        super.onPause();
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
    public boolean onBackPressed() {
        if (mBottomSheet != null && mBottomSheet.isInAdvancedMode()) {
            mBottomSheet.setAdvancedMode(false);
            return true;
        }
        return false;
    }

    @Override
    public void onToggleAdvancedMode(boolean advancedMode) {
        BaseActivity activity = getBaseActivity();
        if (activity != null) {
            activity.collapseAppBar();
            activity.setAppBarLocked(advancedMode);
        }
        mBottomSheet.resetPeekHeight(0);
    }

    @Override
    public void onScrollingInBasicEditor(boolean scrolling) {
        getBaseActivity().setAppBarLocked(scrolling);
    }

    @Override
    protected void setHighlightColors(int colorAttrId, int statusBarColorAttrId) {
        super.setHighlightColors(colorAttrId, statusBarColorAttrId);
        mBottomSheet.setHighlightColor(colorAttrId);
    }

    @Override
    protected RootAdapter<GitLabComment, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        mAdapter = new CommitCommentAdapter(getActivity(), mRepoOwner, mRepoName, this);
        return mAdapter;
    }

    @Override
    protected void onAddData(RootAdapter<GitLabComment, ?> adapter, List<GitLabComment> data) {
        super.onAddData(adapter, data);
        Set<GitLabUser> users = mAdapter.getUsers();
        if (mCommitAuthor != null) {
            users.add(mCommitAuthor);
        }
        if (mCommitter != null) {
            users.add(mCommitter);
        }
        mBottomSheet.setMentionUsers(users);

        if (mInitialComment != null) {
            for (int i = 0; i < data.size(); i++) {
                if (mInitialComment.matches(data.get(i).id(), data.get(i).createdAtDate())) {
                    scrollToAndHighlightPosition(i);
                    break;
                }
            }
            mInitialComment = null;
        }
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_comments_found;
    }

    @Override
    protected Single<List<GitLabComment>> onCreateDataSingle(boolean bypassCache) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, bypassCache);
        return com.gl4a.utils.SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getCommitDiscussions(projectId, mObjectSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .map(discussions -> {
                    // Flatten discussions → notes, keeping only non-positional notes.
                    java.util.List<GitLabComment> notes = new java.util.ArrayList<>();
                    for (com.gl4a.gitlab.model.GitLabCommitDiscussion d : discussions) {
                        if (d.notes != null) {
                            for (GitLabComment note : d.notes) {
                                if (note.position() == null) notes.add(note);
                            }
                        }
                    }
                    return notes;
                });
    }

    @Override
    protected List<GitLabComment> onGetInitialData() {
        // Always fetch via discussions API so system notes have system=true set correctly.
        // The initial comments bundle (from /comments endpoint) lacks the system field.
        return null;
    }

    @Override
    public void editComment(GitLabComment comment) {
        Intent intent = EditCommitCommentActivity.makeIntent(getActivity(),
                mRepoOwner, mRepoName, mObjectSha, comment.id(), comment.body());
        mEditLauncher.launch(intent);
    }

    @Override
    public void deleteComment(final GitLabComment comment) {
        ConfirmationDialogFragment.show(this, R.string.delete_comment_message,
                R.string.delete, comment, "deleteconfirm");
    }

    @Override
    public void onConfirmed(String tag, Parcelable data) {
        GitLabComment comment = (GitLabComment) data;
        deleteComment(comment.id());
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
    public int getCommentEditorHintResId() {
        return R.string.commit_comment_hint;
    }

    @Override
    public Single<?> onEditorDoSend(String comment) {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, false);
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("note", comment);
        return com.gl4a.utils.SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.createCommitComment(projectId, mObjectSha, request))
                .map(ApiHelpers::throwOnFailure);
    }

    @Override
    public void onEditorTextSent() {
        // Reload this fragment's own list so the new comment appears immediately.
        onRefresh();
        // Also notify the activity so it keeps its mComments in sync.
        refreshComments();
    }

    @Override
    public int getEditorErrorMessageResId() {
        return R.string.issue_error_comment;
    }

    private void refreshComments() {
        if (getActivity() instanceof CommentUpdateListener) {
            ((CommentUpdateListener) getActivity()).onCommentsUpdated();
        }
    }

    private void deleteComment(long id) {
        // GitLab commit comments deletion is not yet supported; stub as no-op
        refreshComments();
    }
}
