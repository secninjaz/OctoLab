package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabReview;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.activities.ReviewActivity;
import com.gl4a.utils.IntentUtils;

import java.util.Optional;

import io.reactivex.Single;

public class PullRequestReviewLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final int mPullRequestNumber;
    @VisibleForTesting
    protected final IntentUtils.InitialCommentMarker mMarker;

    public PullRequestReviewLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, int pullRequestNumber, IntentUtils.InitialCommentMarker marker) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mPullRequestNumber = pullRequestNumber;
        mMarker = marker;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        // GitLab has no separate review endpoint — return an empty stub review
        GitLabReview review = new GitLabReview();
        return Single.just(Optional.of(ReviewActivity.makeIntent(mActivity,
                mRepoOwner, mRepoName, mPullRequestNumber, review, mMarker)));
    }
}
