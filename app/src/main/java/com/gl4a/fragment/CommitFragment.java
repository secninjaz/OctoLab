package com.gl4a.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.activities.CommitDiffViewerActivity;
import com.gl4a.activities.FileViewerActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;
import com.gl4a.utils.UiUtils;
import com.vdurmont.emoji.EmojiParser;

import java.util.ArrayList;
import java.util.List;

public class CommitFragment extends LoadingFragmentBase implements OnClickListener {
    public static CommitFragment newInstance(String repoOwner, String repoName, String commitSha,
            GitLabCommit commit, List<GitLabComment> comments) {
        CommitFragment f = new CommitFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putString("sha", commitSha);
        IntentUtils.putCompressedValueToBundle(args, "commit", commit);
        IntentUtils.putCompressedValueToBundle(args, "comments", comments);
        f.setArguments(args);
        return f;
    }

    public interface CommentUpdateListener {
        void onCommentsUpdated();
    }

    private String mRepoOwner;
    private String mRepoName;
    private String mObjectSha;
    private GitLabCommit mCommit;
    private List<GitLabComment> mComments;
    protected View mContentView;

    // Diffs that arrived before onCreateView — applied in onViewCreated.
    private List<GitLabDiff> mPendingDiffs;
    private List<GitLabComment> mPendingDiffComments;

    private final ActivityResultLauncher<Intent> mDiffViewerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                if (getActivity() instanceof CommentUpdateListener) {
                    ((CommentUpdateListener) getActivity()).onCommentsUpdated();
                }
            }));

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mRepoOwner = args.getString("owner");
        mRepoName = args.getString("repo");
        mObjectSha = args.getString("sha");
        mCommit = IntentUtils.readCompressedValueFromBundle(args, "commit");
        mComments = IntentUtils.readCompressedValueFromBundle(args, "comments");
    }

    @Override
    protected View onCreateContentView(LayoutInflater inflater, ViewGroup parent) {
        mContentView = inflater.inflate(R.layout.commit, parent, false);
        return mContentView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        populateUiIfReady();
        if (mPendingDiffs != null) {
            fillStatsFromDiffs(mPendingDiffs, mPendingDiffComments);
            mPendingDiffs = null;
            mPendingDiffComments = null;
        }
    }

    @Override
    public void onRefresh() {
        // data arrives through constructor arguments
    }

    protected void populateUiIfReady() {
        if (mCommit == null) {
            // Data not yet delivered — nothing to populate yet.
            return;
        }
        fillHeader();
        fillStats(mCommit, mComments);
    }

    private void fillHeader() {
        final Activity activity = getActivity();
        final Gl4Application app = Gl4Application.get();

        ImageView ivGravatar = mContentView.findViewById(R.id.iv_gravatar);
        AvatarHandler.assignAvatar(ivGravatar, mCommit.author());
        ivGravatar.setOnClickListener(this);
        ivGravatar.setTag(mCommit.authorEmail);

        TextView tvMessage = mContentView.findViewById(R.id.tv_message);
        TextView tvTitle = mContentView.findViewById(R.id.tv_title);

        GitLabCommit.GitLabCommitDetail commitDetail = mCommit.commit();
        String message = (commitDetail != null && commitDetail.message() != null)
                ? commitDetail.message() : "";
        int pos = message.indexOf('\n');
        String title = pos > 0 ? message.substring(0, pos) : message;
        title = EmojiParser.parseToUnicode(title);
        int length = message.length();
        while (pos > 0 && pos < length && Character.isWhitespace(message.charAt(pos))) {
            pos++;
        }
        message = pos > 0 && pos < length ? message.substring(pos) : null;
        if (message != null) {
            message = EmojiParser.parseToUnicode(message);
        }

        tvTitle.setText(title);
        tvMessage.setText(message);
        tvTitle.setVisibility(StringUtils.isBlank(title) ? View.GONE : View.VISIBLE);
        tvMessage.setVisibility(StringUtils.isBlank(message) ? View.GONE : View.VISIBLE);

        TextView tvAuthor = mContentView.findViewById(R.id.tv_author);
        tvAuthor.setText(ApiHelpers.getAuthorName(app, mCommit));

        TextView tvTimestamp = mContentView.findViewById(R.id.tv_timestamp);
        String authorDate = (commitDetail != null && commitDetail.author() != null)
                ? commitDetail.author().date() : null;
        tvTimestamp.setText(StringUtils.formatRelativeTime(activity, authorDate, true));

        View committerContainer = mContentView.findViewById(R.id.committer);

        if (!ApiHelpers.authorEqualsCommitter(mCommit)) {
            ImageView commitGravatar = mContentView.findViewById(R.id.iv_commit_gravatar);
            TextView commitExtra = mContentView.findViewById(R.id.tv_commit_extra);

            // GitLab doesn't provide a committer user object; use default avatar
            commitGravatar.setImageDrawable(
                    new AvatarHandler.DefaultAvatarDrawable(mCommit.committerName, mCommit.committerEmail));
            String committerDate = (commitDetail != null && commitDetail.committer() != null)
                    ? commitDetail.committer().date() : null;
            String committerText = getString(R.string.commit_details,
                    mCommit.committerName != null ? mCommit.committerName : "",
                    StringUtils.formatRelativeTime(activity, committerDate, true));
            StringUtils.applyBoldTagsAndSetText(commitExtra, committerText);

            committerContainer.setVisibility(View.VISIBLE);
        } else {
            committerContainer.setVisibility(View.GONE);
        }
    }

    protected void fillStats(GitLabCommit commit, List<GitLabComment> comments) {
        // Retrieve diffs that were pre-fetched and stored as commit's diff list
        // (the CommitActivity supplies these via the newInstance call)
        // For now, use diffs stored via the commit model adapter
        LinearLayout llChanged = mContentView.findViewById(R.id.ll_changed);
        LinearLayout llAdded = mContentView.findViewById(R.id.ll_added);
        LinearLayout llRenamed = mContentView.findViewById(R.id.ll_renamed);
        LinearLayout llDeleted = mContentView.findViewById(R.id.ll_deleted);
        llChanged.removeAllViews();
        llAdded.removeAllViews();
        llRenamed.removeAllViews();
        llDeleted.removeAllViews();

        // diffs are passed externally; this fragment just displays them
        // The calling activity must populate via fillStatsFromDiffs
        TextView tvSummary = mContentView.findViewById(R.id.tv_desc);
        tvSummary.setText("");

        adjustVisibility(R.id.card_added, 0);
        adjustVisibility(R.id.card_changed, 0);
        adjustVisibility(R.id.card_renamed, 0);
        adjustVisibility(R.id.card_deleted, 0);
    }

    public void fillStatsFromDiffs(List<GitLabDiff> diffs, List<GitLabComment> comments) {
        if (mContentView == null) {
            mPendingDiffs = diffs;
            mPendingDiffComments = comments;
            return;
        }
        LinearLayout llChanged = mContentView.findViewById(R.id.ll_changed);
        LinearLayout llAdded = mContentView.findViewById(R.id.ll_added);
        LinearLayout llRenamed = mContentView.findViewById(R.id.ll_renamed);
        LinearLayout llDeleted = mContentView.findViewById(R.id.ll_deleted);
        llChanged.removeAllViews();
        llAdded.removeAllViews();
        llRenamed.removeAllViews();
        llDeleted.removeAllViews();

        int addedFiles = 0, changedFiles = 0, renamedFiles = 0, deletedFiles = 0;
        int totalAdditions = 0, totalDeletions = 0;
        int filesCount = diffs != null ? diffs.size() : 0;
        int highlightColor = UiUtils.resolveColor(getActivity(), android.R.attr.textColorPrimary);
        ForegroundColorSpan additionsSpan = new ForegroundColorSpan(
                UiUtils.resolveColor(getActivity(), R.attr.colorCommitAddition));
        ForegroundColorSpan deletionsSpan = new ForegroundColorSpan(
                UiUtils.resolveColor(getActivity(), R.attr.colorCommitDeletion));

        for (int i = 0; i < filesCount; i++) {
            GitLabDiff diff = diffs.get(i);
            final LinearLayout parent;

            switch (diff.status()) {
                case "added":
                    parent = llAdded;
                    addedFiles++;
                    break;
                case "modified":
                    parent = llChanged;
                    changedFiles++;
                    break;
                case "renamed":
                    parent = llRenamed;
                    renamedFiles++;
                    break;
                case "removed":
                    parent = llDeleted;
                    deletedFiles++;
                    break;
                default:
                    continue;
            }

            totalAdditions += diff.additions();
            totalDeletions += diff.deletions();

            View fileView = getLayoutInflater().inflate(R.layout.commit_filename, parent, false);
            TextView fileNameView = fileView.findViewById(R.id.filename);

            fillFileName(fileNameView, diff);
            fillFileStats(fileView, diff, additionsSpan, deletionsSpan);
            fillFileCommentsCount(fileView, diff, comments);

            if (diff.patch() != null ||
                    (parent != llDeleted && FileUtils.isImage(diff.filename()))) {
                fileNameView.setTextColor(highlightColor);
                fileView.setOnClickListener(this);
                fileView.setTag(diff);
            }

            parent.addView(fileView);
        }

        adjustVisibility(R.id.card_added, addedFiles);
        adjustVisibility(R.id.card_changed, changedFiles);
        adjustVisibility(R.id.card_renamed, renamedFiles);
        adjustVisibility(R.id.card_deleted, deletedFiles);

        TextView tvSummary = mContentView.findViewById(R.id.tv_desc);
        tvSummary.setText(getString(R.string.commit_summary,
                addedFiles + changedFiles + renamedFiles + deletedFiles,
                totalAdditions, totalDeletions));
    }

    private void fillFileName(TextView fileNameView, GitLabDiff diff) {
        if (diff.previousFilename() != null && !diff.previousFilename().equals(diff.filename())) {
            SpannableStringBuilder fileNames = new SpannableStringBuilder();
            fileNames.append(diff.previousFilename()).append('\n').append(diff.filename());
            fileNames.setSpan(new StrikethroughSpan(), 0, diff.previousFilename().length(), 0);
            fileNameView.setText(fileNames);
        } else {
            fileNameView.setText(diff.filename());
        }
    }

    private void fillFileStats(View fileView, GitLabDiff diff, ForegroundColorSpan additionsSpan,
            ForegroundColorSpan deletionsSpan) {
        TextView statsView = fileView.findViewById(R.id.stats);
        if (diff.additions() > 0 || diff.deletions() > 0) {
            SpannableStringBuilder stats = new SpannableStringBuilder();
            stats.append("+").append(String.valueOf(diff.additions()));
            int addLength = stats.length();
            stats.setSpan(additionsSpan, 0, addLength, 0);
            stats.append("   -").append(String.valueOf(diff.deletions()));
            stats.setSpan(deletionsSpan, addLength, stats.length(), 0);
            statsView.setText(stats);
            statsView.setVisibility(View.VISIBLE);
        } else {
            statsView.setVisibility(View.GONE);
        }
    }

    private void fillFileCommentsCount(View fileView, GitLabDiff diff,
            List<GitLabComment> comments) {
        if (comments == null) return;
        int commentCount = 0;
        for (GitLabComment comment : comments) {
            // Only count comments whose path matches this diff file
            if (diff.filename() != null && diff.filename().equals(comment.path())) {
                commentCount++;
            }
        }
        if (commentCount > 0) {
            TextView commentView = fileView.findViewById(R.id.comments);
            commentView.setText(String.valueOf(commentCount));
            commentView.setVisibility(View.VISIBLE);
        }
    }

    private void adjustVisibility(int containerResId, int count) {
        int visibility = count > 0 ? View.VISIBLE : View.GONE;
        mContentView.findViewById(containerResId).setVisibility(visibility);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.iv_gravatar) {
            GitLabUser user = mCommit.authorUser != null
                    ? mCommit.authorUser
                    : AvatarHandler.getCachedUserForEmail((String) v.getTag());
            if (user != null) {
                Intent intent = UserActivity.makeIntent(getActivity(), user);
                if (intent != null) startActivity(intent);
            }
        } else {
            GitLabDiff diff = (GitLabDiff) v.getTag();
            handleFileClick(diff);
        }
    }

    protected void handleFileClick(GitLabDiff diff) {
        final Intent intent;
        if (FileUtils.isImage(diff.filename())) {
            intent = FileViewerActivity.makeIntent(getActivity(), mRepoOwner, mRepoName,
                    mObjectSha, diff.filename());
        } else {
            intent = CommitDiffViewerActivity.makeIntent(getActivity(), mRepoOwner, mRepoName,
                    mObjectSha, diff.filename(), diff.patch(),
                    commentsForFile(diff), -1, -1, false, null);
        }
        mDiffViewerLauncher.launch(intent);
    }

    private ArrayList<GitLabComment> commentsForFile(GitLabDiff diff) {
        if (mComments == null) {
            return null;
        }
        // GitLab commit comments are not path-scoped; return all
        return new ArrayList<>(mComments);
    }
}
