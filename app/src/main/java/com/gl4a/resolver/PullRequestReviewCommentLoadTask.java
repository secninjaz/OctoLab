package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabReview;
import com.gl4a.gitlab.service.GitLabMergeRequestService;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.ReviewActivity;
import com.gl4a.model.TimelineItem;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.reactivex.Single;

public class PullRequestReviewCommentLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final int mPullRequestNumber;
    @VisibleForTesting
    protected final IntentUtils.InitialCommentMarker mMarker;

    public PullRequestReviewCommentLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, int pullRequestNumber, IntentUtils.InitialCommentMarker marker) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mPullRequestNumber = pullRequestNumber;
        mMarker = marker;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        return load(mActivity, mRepoOwner, mRepoName, mPullRequestNumber, mMarker);
    }

    public static Single<Optional<Intent>> load(Context context, String repoOwner, String repoName,
            int pullRequestNumber, IntentUtils.InitialCommentMarker marker) {
        return SingleFactory.getProjectId(repoOwner, repoName)
                .flatMap(projectId -> {
                    GitLabMergeRequestService commentService =
                            ServiceFactory.get(GitLabMergeRequestService.class, false);
                    return commentService.getComments(projectId, pullRequestNumber, "asc", 1, 100)
                            .map(ApiHelpers::throwOnFailure);
                })
                .compose(RxUtils.<GitLabComment>sortList(ApiHelpers.COMMENT_COMPARATOR))
                .flatMap(comments -> {
                    Map<String, GitLabComment> commentsByDiffHunkId = new HashMap<>();
                    for (GitLabComment comment : comments) {
                        String id = TimelineItem.Diff.getDiffHunkId(comment);
                        if (!commentsByDiffHunkId.containsKey(id)) {
                            commentsByDiffHunkId.put(id, comment);
                        }
                        if (marker.matches(comment.id(), null)) {
                            GitLabReview review = new GitLabReview();
                            return Single.just(Optional.of(ReviewActivity.makeIntent(context,
                                    repoOwner, repoName, pullRequestNumber, review, marker)));
                        }
                    }
                    return Single.just(Optional.<Intent>empty());
                });
    }
}
