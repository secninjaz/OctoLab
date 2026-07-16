package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabComment;
import com.gl4a.gitlab.model.GitLabCommit;
import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.service.GitLabCommitService;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.CommitActivity;
import com.gl4a.activities.CommitDiffViewerActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.List;
import java.util.Optional;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class CommitCommentLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final String mCommitSha;
    @VisibleForTesting
    protected final IntentUtils.InitialCommentMarker mMarker;

    public CommitCommentLoadTask(FragmentActivity activity, Uri urlToResolve, String repoOwner,
            String repoName, String commitSha, IntentUtils.InitialCommentMarker marker) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mCommitSha = commitSha;
        mMarker = marker;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        return load(mActivity, mRepoOwner, mRepoName, mCommitSha, mMarker);
    }

    public static Single<Optional<Intent>> load(Context context,
            String repoOwner, String repoName, String commitSha,
            IntentUtils.InitialCommentMarker marker) {
        GitLabCommitService commitService = ServiceFactory.get(GitLabCommitService.class, false);

        Single<GitLabCommit> commitSingle = SingleFactory.getProjectId(repoOwner, repoName)
                .flatMap(projectId -> commitService.getCommit(projectId, commitSha)
                        .map(ApiHelpers::throwOnFailure))
                .subscribeOn(Schedulers.io())
                .cache();

        Single<List<GitLabComment>> commentSingle = SingleFactory.getProjectId(repoOwner, repoName)
                .flatMap(projectId -> commitService.getCommitComments(projectId, commitSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .subscribeOn(Schedulers.io())
                .cache();

        Single<List<GitLabDiff>> diffSingle = SingleFactory.getProjectId(repoOwner, repoName)
                .flatMap(projectId -> commitService.getCommitDiff(projectId, commitSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure))
                .subscribeOn(Schedulers.io())
                .cache();

        Single<Optional<GitLabDiff>> fileSingle = commentSingle
                .compose(RxUtils.filterAndMapToFirst(c -> marker.matches(c.id(), c.createdAtDate())))
                .zipWith(diffSingle, (comment, diffs) -> {
                    if (comment.isPresent()) {
                        String commentPath = comment.get().path();
                        for (GitLabDiff diff : diffs) {
                            if (diff.newPath != null && diff.newPath.equals(commentPath)) {
                                return Optional.of(diff);
                            }
                        }
                    }
                    return Optional.<GitLabDiff>empty();
                });

        return Single.zip(commitSingle, commentSingle, fileSingle, (commit, comments, fileOpt) -> {
            GitLabDiff file = fileOpt.orElse(null);
            if (file != null && !FileUtils.isImage(file.filename())) {
                return Optional.of(CommitDiffViewerActivity.makeIntent(context,
                        repoOwner, repoName, commitSha, file.filename(), file.patch(),
                        comments, -1, -1, false, marker));
            } else if (file == null) {
                return Optional.of(
                        CommitActivity.makeIntent(context, repoOwner, repoName, commitSha, marker));
            }
            return Optional.<Intent>empty();
        });
    }
}
