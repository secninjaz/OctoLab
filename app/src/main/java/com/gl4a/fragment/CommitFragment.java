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
    // A large commit shows its first files, more on request, so drawing them doesn't stall the
    // screen (#272)
    private static final int FILES_FIRST_SHOWN = 100;
    private static final int FILES_MORE_STEP = 200;
    private int mFilesShown = FILES_FIRST_SHOWN;

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
        loadDetails();
    }

    /**
     * Parent, branches, tags and merge requests of the commit, as on GitLab web (#273): compact,
     * with the default branch and first tag shown and the rest a tap away.
     */
    @SuppressWarnings("unchecked")
    private void loadDetails() {
        if (mDetailsLoading) return;
        mDetailsLoading = true;
        com.gl4a.gitlab.service.GitLabCommitService service =
                com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabCommitService.class,
                        false);
        final String sha = mCommit.sha() != null ? mCommit.sha() : mObjectSha;
        // GitLab's own path for the project, from the commit's web URL, not names shown on
        // screen (#244)
        String[] project = ApiHelpers.projectFromWebUrl(mCommit.webUrl);
        final String owner = project != null ? project[0] : mRepoOwner;
        final String repo = project != null ? project[1] : mRepoName;
        io.reactivex.Single<List<java.util.Map<String, Object>>> refs =
                com.gl4a.utils.SingleFactory.getProjectId(owner, repo)
                        .flatMap(projectId -> ApiHelpers.<java.util.Map<String, Object>>allPages(
                                page -> service.getCommitRefs(projectId, sha, page, 100)))
                        .onErrorReturnItem(new java.util.ArrayList<>());
        io.reactivex.Single<List<com.gl4a.gitlab.model.GitLabIssue>> mrs =
                com.gl4a.utils.SingleFactory.getProjectId(owner, repo)
                        .flatMap(projectId -> service.getCommitMergeRequests(projectId, sha))
                        .map(ApiHelpers::throwOnFailure)
                        .onErrorReturnItem(new java.util.ArrayList<>());
        // The project's default branch, shown first among the branches as GitLab web does
        io.reactivex.Single<String> defaultBranch = com.gl4a.ServiceFactory.get(
                        com.gl4a.gitlab.service.GitLabProjectService.class, false)
                .getProjectByPath(android.net.Uri.encode(owner + "/" + repo))
                .map(ApiHelpers::throwOnFailure)
                .map(p -> p.defaultBranch() != null ? p.defaultBranch() : "")
                .onErrorReturnItem("");
        io.reactivex.Single.zip(refs, mrs, defaultBranch,
                        (r, m, d) -> new Object[] { r, m, d })
                .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(result -> showDetails(
                                (List<java.util.Map<String, Object>>) result[0],
                                (List<com.gl4a.gitlab.model.GitLabIssue>) result[1],
                                (String) result[2], owner, repo),
                        error -> { });
    }

    private boolean mDetailsLoading;

    private void showDetails(List<java.util.Map<String, Object>> refs,
            List<com.gl4a.gitlab.model.GitLabIssue> mergeRequests, String defaultBranch,
            String owner, String repo) {
        if (mContentView == null || getContext() == null) return;
        android.content.Context context = getContext();
        LinearLayout container = mContentView.findViewById(R.id.ll_commit_details);
        container.removeAllViews();

        // Parent(s), each opening that commit
        List<String> parents = mCommit.parentIds;
        if (parents != null && !parents.isEmpty()) {
            SpannableStringBuilder text = new SpannableStringBuilder(getString(
                    parents.size() > 1 ? R.string.commit_parents : R.string.commit_parent));
            for (String parent : parents) {
                text.append("  ");
                int start = text.length();
                text.append(parent.substring(0, Math.min(8, parent.length())));
                text.setSpan(new com.gl4a.widget.IntentSpan(context,
                                c -> com.gl4a.activities.CommitActivity.makeIntent(c,
                                        owner, repo, parent)),
                        start, text.length(), 0);
                text.setSpan(new android.text.style.TypefaceSpan("monospace"),
                        start, text.length(), 0);
            }
            container.addView(detailRow(context, R.drawable.commit, text, null));
        }

        List<String> branches = new java.util.ArrayList<>();
        List<String> tags = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> ref : refs) {
            Object name = ref.get("name");
            if (!(name instanceof String)) continue;
            if ("tag".equals(ref.get("type"))) tags.add((String) name);
            else branches.add((String) name);
        }
        if (!branches.isEmpty()) {
            String first = branches.contains(defaultBranch)
                    ? defaultBranch : branches.get(0);
            container.addView(refsRow(context, R.drawable.branch, R.string.commit_branches,
                    first, branches, R.string.commit_branches_containing));
        }
        if (!tags.isEmpty()) {
            container.addView(refsRow(context, R.drawable.tag, R.string.commit_tags,
                    tags.get(0), tags, R.string.commit_tags_containing));
        }

        // Merge requests the commit is part of, each opening the MR
        if (mergeRequests.isEmpty()) {
            container.addView(detailRow(context, R.drawable.pull_request,
                    getString(R.string.commit_no_related_mrs), null));
        } else {
            for (com.gl4a.gitlab.model.GitLabIssue mr : mergeRequests) {
                String line = "!" + mr.iid + " " + (mr.title != null ? mr.title : "");
                View row = detailRow(context, R.drawable.pull_request, line, null);
                if (mr.webUrl != null) {
                    row.setBackgroundResource(android.R.drawable.list_selector_background);
                    row.setOnClickListener(v -> com.gl4a.utils.IntentUtils
                            .openLinkInternallyOrExternally(requireActivity(),
                                    android.net.Uri.parse(mr.webUrl)));
                }
                container.addView(row);
            }
        }
        mContentView.findViewById(R.id.card_details).setVisibility(
                container.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** A details row: an icon, then text, then an optional view (e.g. pills) below. */
    private View detailRow(android.content.Context context, int icon, CharSequence text,
            @androidx.annotation.Nullable View below) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * density);
        row.setPadding(0, pad, 0, pad);
        TextView label = new TextView(context);
        label.setTextAppearance(android.R.style.TextAppearance_Small);
        label.setTextColor(UiUtils.resolveColor(context, android.R.attr.textColorPrimary));
        label.setText(text);
        // Links (parents, "… containing commit") only where there are any, so a merge
        // request row's own tap still works
        if (text instanceof android.text.Spanned && ((android.text.Spanned) text).getSpans(0,
                text.length(), android.text.style.ClickableSpan.class).length > 0) {
            label.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        android.graphics.drawable.Drawable drawable =
                androidx.core.content.ContextCompat.getDrawable(context, icon).mutate();
        int size = Math.round(18 * density);
        drawable.setBounds(0, 0, size, size);
        drawable.setTint(UiUtils.resolveColor(context, android.R.attr.textColorSecondary));
        label.setCompoundDrawablesRelative(drawable, null, null, null);
        label.setCompoundDrawablePadding(Math.round(8 * density));
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label);
        if (below != null) row.addView(below);
        return row;
    }

    /**
     * Branches or tags: the label, the first one as a pill and "… containing commit", which
     * shows all of them as pills (#273).
     */
    private View refsRow(android.content.Context context, int icon, int labelRes, String first,
            List<String> all, int containingRes) {
        float density = context.getResources().getDisplayMetrics().density;
        com.google.android.material.chip.ChipGroup pills =
                new com.google.android.material.chip.ChipGroup(context);
        pills.setChipSpacingHorizontal(Math.round(6 * density));
        pills.setChipSpacingVertical(Math.round(6 * density));
        pills.setPadding(Math.round(26 * density), Math.round(4 * density), 0, 0);
        pills.setVisibility(View.GONE);
        for (String name : all) {
            pills.addView(pill(context, name));
        }
        SpannableStringBuilder text = new SpannableStringBuilder(getString(labelRes));
        text.append("  ");
        int start = text.length();
        text.append(" ").append(first).append(" ");
        text.setSpan(new android.text.style.BackgroundColorSpan(
                UiUtils.resolveColor(context, android.R.attr.colorControlHighlight)),
                start, text.length(), 0);
        if (all.size() > 1 || !all.contains(first)) {
            text.append("   ");
            int linkStart = text.length();
            text.append("\u25B8 ").append(getString(containingRes));
            text.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(@androidx.annotation.NonNull View widget) {
                    pills.setVisibility(pills.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE);
                }

                @Override
                public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                    ds.setColor(UiUtils.resolveColor(context, android.R.attr.textColorLink));
                    ds.setUnderlineText(false);
                }
            }, linkStart, text.length(), 0);
        }
        return detailRow(context, icon, text, pills);
    }

    private TextView pill(android.content.Context context, String name) {
        float density = context.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(context);
        pill.setText(name);
        pill.setTextAppearance(android.R.style.TextAppearance_Small);
        pill.setTextColor(UiUtils.resolveColor(context, android.R.attr.textColorPrimary));
        pill.setBackgroundResource(R.drawable.shortcut_pill);
        int horizontal = Math.round(10 * density), vertical = Math.round(3 * density);
        pill.setPadding(horizontal, vertical, horizontal, vertical);
        return pill;
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
        int shownAdded = 0, shownChanged = 0, shownRenamed = 0, shownDeleted = 0;
        int totalAdditions = 0, totalDeletions = 0;
        int filesCount = diffs != null ? diffs.size() : 0;
        int shown = 0;
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
            if (shown >= mFilesShown) {
                continue;
            }
            shown++;
            if (parent == llAdded) shownAdded++;
            else if (parent == llChanged) shownChanged++;
            else if (parent == llRenamed) shownRenamed++;
            else shownDeleted++;

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

        adjustVisibility(R.id.card_added, shownAdded);
        adjustVisibility(R.id.card_changed, shownChanged);
        adjustVisibility(R.id.card_renamed, shownRenamed);
        adjustVisibility(R.id.card_deleted, shownDeleted);

        // GitLab's own totals for the commit, as GitLab web shows them; adding up the diffs
        // missed what GitLab doesn't send in them (#272)
        if (mCommit != null && mCommit.stats != null) {
            totalAdditions = mCommit.stats.additions;
            totalDeletions = mCommit.stats.deletions;
        }
        TextView tvSummary = mContentView.findViewById(R.id.tv_desc);
        tvSummary.setText(getString(R.string.commit_summary,
                addedFiles + changedFiles + renamedFiles + deletedFiles,
                totalAdditions, totalDeletions));

        TextView showMore = mContentView.findViewById(R.id.tv_show_more_files);
        int listed = addedFiles + changedFiles + renamedFiles + deletedFiles;
        if (shown < listed) {
            showMore.setText(getString(R.string.commit_show_more_files, listed - shown, listed));
            showMore.setVisibility(View.VISIBLE);
            showMore.setOnClickListener(v -> {
                mFilesShown += FILES_MORE_STEP;
                fillStatsFromDiffs(diffs, comments);
            });
        } else {
            showMore.setVisibility(View.GONE);
        }
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
            // The one shared way to open a commit author's profile, as in commit lists (#244)
            AvatarHandler.openGitAuthorProfile(getActivity(), mCommit);
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
