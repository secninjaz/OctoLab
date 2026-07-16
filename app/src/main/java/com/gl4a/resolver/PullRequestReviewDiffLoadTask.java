package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabReview;
import com.gl4a.gitlab.service.GitLabMergeRequestService;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.ReviewActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.Optional;

import io.reactivex.Single;

public class PullRequestReviewDiffLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final DiffHighlightId mDiffId;
    @VisibleForTesting
    protected final int mPullRequestNumber;

    public PullRequestReviewDiffLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, DiffHighlightId diffId, int pullRequestNumber) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mDiffId = diffId;
        mPullRequestNumber = pullRequestNumber;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        long diffCommentId = Long.parseLong(mDiffId.fileHash);
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> {
                    GitLabMergeRequestService service =
                            ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return service.getComments(projectId, mPullRequestNumber, "asc", 1, 100)
                            .map(ApiHelpers::throwOnFailure)
                            .map(comments -> {
                                for (com.gl4a.gitlab.model.GitLabComment c : comments) {
                                    if (c.id() == diffCommentId) return java.util.Optional.of(c);
                                }
                                return java.util.Optional.<com.gl4a.gitlab.model.GitLabComment>empty();
                            });
                })
                .map(commentOpt -> {
                    if (commentOpt.isPresent()) {
                        GitLabReview review = new GitLabReview();
                        return Optional.of(ReviewActivity.makeIntent(
                                mActivity, mRepoOwner, mRepoName, mPullRequestNumber, review,
                                new IntentUtils.InitialCommentMarker(diffCommentId)));
                    }
                    return Optional.<Intent>empty();
                });
    }
}
