package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.service.GitLabMergeRequestService;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.PullRequestActivity;
import com.gl4a.activities.PullRequestDiffViewerActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.Collections;
import java.util.List;

import io.reactivex.Single;

public class PullRequestDiffLoadTask extends DiffLoadTask {
    private final int mPullRequestNumber;
    private final int mPage;

    public PullRequestDiffLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, DiffHighlightId diffId, int pullRequestNumber, int page) {
        super(activity, urlToResolve, repoOwner, repoName, diffId);
        mPullRequestNumber = pullRequestNumber;
        mPage = page;
    }

    @Override
    protected @NonNull Intent getLaunchIntent(String sha, @NonNull GitLabDiff file, DiffHighlightId diffId) {
        // Open the specific diff file with highlighted lines in PullRequestDiffViewerActivity.
        return PullRequestDiffViewerActivity.makeIntent(
                mActivity, mRepoOwner, mRepoName, mPullRequestNumber,
                sha, file.filename(), file.patch(),
                Collections.emptyList(),
                -1,
                diffId.startLine, diffId.endLine, diffId.right,
                null);
    }

    @NonNull
    @Override
    protected Intent getFallbackIntent(String sha) {
        return PullRequestActivity.makeIntent(mActivity, mRepoOwner, mRepoName,
                mPullRequestNumber, mPage, null);
    }

    @Override
    protected Single<String> getSha() {
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> {
                    GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return service.getMergeRequest(projectId, mPullRequestNumber)
                            .map(ApiHelpers::throwOnFailure)
                            .map(mr -> mr.head().sha());
                });
    }

    @Override
    protected Single<List<GitLabDiff>> getFiles() {
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> {
                    GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return service.getDiffs(projectId, mPullRequestNumber, 1, 100)
                            .map(ApiHelpers::throwOnFailure);
                });
    }
}
