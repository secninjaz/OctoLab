package com.gl4a.fragment;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.service.GitLabCommitService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;
import com.gl4a.widget.LinkSpan;

import java.util.List;

/**
 * The commit screen for an MR version compared with the previous one (#198): the same file
 * list and diffs as a commit, under a "Version 6 compared with version 5" header listing the
 * version's commits, each opening that commit.
 */
public class MergeRequestVersionFragment extends CommitFragment {
    public static MergeRequestVersionFragment newInstance(String repoOwner, String repoName,
            String fromSha, String toSha, int versionNumber, int previousNumber) {
        MergeRequestVersionFragment f = new MergeRequestVersionFragment();
        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        // Files are opened at the version's head
        args.putString("sha", toSha);
        args.putString("from", fromSha);
        args.putInt("version", versionNumber);
        args.putInt("previous", previousNumber);
        f.setArguments(args);
        return f;
    }

    private static final int ID_LOADER_COMPARE = 0;
    // A version can bring thousands of commits (e.g. a rebase onto the target branch: "added
    // 2896 commits"); listing and linking them all froze the screen (ANR), #198
    private static final int MAX_COMMITS_SHOWN = 10;
    private static final int MAX_FILES_SHOWN = 200;

    private GitLabCommitService.GitLabCompare mCompare;

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mCompare != null) {
            fillVersion();
        } else {
            setContentShown(false);
            loadCompare();
        }
    }

    @Override
    protected void populateUiIfReady() {
        // Filled from the compare result, there's no single commit
    }

    private void loadCompare() {
        Bundle args = getArguments();
        String owner = args.getString("owner");
        String repo = args.getString("repo");
        String from = args.getString("from");
        String to = args.getString("sha");
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, false);
        SingleFactory.getProjectId(owner, repo)
                .flatMap(projectId -> service.compareCommits(projectId, from, to))
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_COMPARE, false))
                .subscribe(compare -> {
                    mCompare = compare;
                    if (mContentView != null) {
                        fillVersion();
                    }
                }, this::handleLoadFailure);
    }

    private void fillVersion() {
        Bundle args = getArguments();
        int version = args.getInt("version");
        int previous = args.getInt("previous");

        mContentView.findViewById(R.id.author).setVisibility(View.GONE);
        mContentView.findViewById(R.id.committer).setVisibility(View.GONE);

        TextView tvTitle = mContentView.findViewById(R.id.tv_title);
        if (version > 0 && previous > 0) {
            tvTitle.setText(getString(R.string.mr_version_compare, version, previous));
        } else if (version > 0) {
            tvTitle.setText(getString(R.string.mr_version_compare_target, version));
        } else {
            tvTitle.setText(R.string.mr_version_compare_previous);
        }
        tvTitle.setVisibility(View.VISIBLE);

        List<GitLabCommit> commits = mCompare.commits;
        List<com.gl4a.gitlab.model.GitLabDiff> diffs = mCompare.diffs;
        int fileCount = diffs != null ? diffs.size() : 0;
        SpannableStringBuilder message = new SpannableStringBuilder();
        if (commits != null && !commits.isEmpty()) {
            message.append(commitList(commits));
        }
        if (fileCount > MAX_FILES_SHOWN) {
            if (message.length() > 0) message.append("\n\n");
            message.append(getString(R.string.mr_version_files_capped, MAX_FILES_SHOWN,
                    fileCount));
            diffs = diffs.subList(0, MAX_FILES_SHOWN);
        }
        TextView tvMessage = mContentView.findViewById(R.id.tv_message);
        tvMessage.setText(message);
        tvMessage.setVisibility(message.length() > 0 ? View.VISIBLE : View.GONE);

        fillStatsFromDiffs(diffs, null);
        setContentShown(true);
    }

    /** "2 commits" and a line per commit, "a1b2c3d4 Title", each linking to the commit. */
    private CharSequence commitList(List<GitLabCommit> commits) {
        Bundle args = getArguments();
        String commitBase = Gl4Application.get().getInstanceUrl() + "/"
                + args.getString("owner") + "/" + args.getString("repo") + "/-/commit/";
        SpannableStringBuilder text = new SpannableStringBuilder(getResources()
                .getQuantityString(R.plurals.mr_version_commits, commits.size(), commits.size()));
        for (GitLabCommit commit : commits.subList(0, Math.min(commits.size(),
                MAX_COMMITS_SHOWN))) {
            String sha = commit.sha();
            if (sha == null) continue;
            text.append('\n');
            int start = text.length();
            text.append(commit.shortId != null ? commit.shortId : sha.substring(0, 8));
            text.setSpan(new LinkSpan(commitBase + sha), start, text.length(), 0);
            if (commit.title != null) {
                text.append(' ').append(commit.title);
            }
        }
        if (commits.size() > MAX_COMMITS_SHOWN) {
            text.append('\n').append(getString(R.string.mr_version_more_commits,
                    commits.size() - MAX_COMMITS_SHOWN));
        }
        return text;
    }
}
