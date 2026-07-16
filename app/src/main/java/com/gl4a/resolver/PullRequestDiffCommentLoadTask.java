package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.model.GitLabMergeRequest;
import com.gl4a.gitlab.service.GitLabMergeRequestService;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;
import androidx.core.util.Pair;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.PullRequestActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.List;
import java.util.Optional;

import io.reactivex.Single;

public class PullRequestDiffCommentLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final int mPullRequestNumber;
    @VisibleForTesting
    protected final IntentUtils.InitialCommentMarker mMarker;
    @VisibleForTesting
    protected final int mPage;

    public PullRequestDiffCommentLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, int pullRequestNumber,
            IntentUtils.InitialCommentMarker marker, int page) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mPullRequestNumber = pullRequestNumber;
        mMarker = marker;
        mPage = page;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        Single<Long> projectIdSingle = SingleFactory.getProjectId(mRepoOwner, mRepoName).cache();

        Single<GitLabMergeRequest> pullRequestSingle = projectIdSingle.flatMap(projectId -> {
            GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
            return service.getMergeRequest(projectId, mPullRequestNumber)
                    .map(ApiHelpers::throwOnFailure);
        });

        Single<List<GitLabComment>> commentsSingle = projectIdSingle.flatMap(projectId -> {
            GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
            return service.getComments(projectId, mPullRequestNumber, "asc", 1, 100)
                    .map(ApiHelpers::throwOnFailure)
                    .compose(RxUtils.<GitLabComment>filter(c -> c.position() != null));
        }).cache();

        Single<List<GitLabDiff>> filesSingle = projectIdSingle.flatMap(projectId -> {
            GitLabMergeRequestService service = ServiceFactory.get(GitLabMergeRequestService.class, false);
            return service.getDiffs(projectId, mPullRequestNumber, 1, 100)
                    .map(ApiHelpers::throwOnFailure);
        });

        return commentsSingle
                .compose(RxUtils.filterAndMapToFirst(c -> mMarker.matches(c.id(), c.createdAtDate())))
                .zipWith(filesSingle, (commentOpt, files) -> commentOpt.map(comment -> {
                    for (GitLabDiff commitFile : files) {
                        if (commitFile.filename() != null && commitFile.filename().equals(comment.path())) {
                            return Pair.create(true, commitFile);
                        }
                    }
                    return Pair.create(comment != null, (GitLabDiff) null);
                }))
                .flatMap(result -> {
                    if (result.isPresent()) {
                        boolean foundComment = result.get().first;
                        GitLabDiff file = result.get().second;
                        if (foundComment && file != null && !FileUtils.isImage(file.filename())) {
                            return Single.zip(pullRequestSingle, commentsSingle, (pr, comments) -> {
                                return Optional.of(PullRequestActivity.makeIntent(mActivity,
                                        mRepoOwner, mRepoName, mPullRequestNumber, mPage, mMarker));
                            });
                        }
                        if (foundComment && file == null) {
                            return Single.just(Optional.of(PullRequestActivity.makeIntent(mActivity,
                                    mRepoOwner, mRepoName, mPullRequestNumber, mPage, mMarker)));
                        }
                    }
                    return Single.just(Optional.<Intent>empty());
                });
    }
}
