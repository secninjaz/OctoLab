package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabDiff;
import com.gl4a.gitlab.service.GitLabCommitService;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ApiRequestException;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.CommitActivity;
import com.gl4a.activities.CommitDiffViewerActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.List;

import io.reactivex.Single;

public class CommitDiffLoadTask extends DiffLoadTask {
    @VisibleForTesting
    protected final String mSha;

    public CommitDiffLoadTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, DiffHighlightId diffId, String sha) {
        super(activity, urlToResolve, repoOwner, repoName, diffId);
        mSha = sha;
    }

    @Override
    protected @NonNull Intent getLaunchIntent(String sha, @NonNull GitLabDiff file, DiffHighlightId diffId) {
        return CommitDiffViewerActivity.makeIntent(mActivity, mRepoOwner, mRepoName,
                sha, file.filename(), file.patch(), null, diffId.startLine,
                diffId.endLine, diffId.right, null);
    }

    @Override
    protected @NonNull Intent getFallbackIntent(String sha) {
        return CommitActivity.makeIntent(mActivity, mRepoOwner, mRepoName, sha);
    }

    @Override
    public Single<String> getSha() {
        return Single.just(mSha);
    }

    @Override
    protected Single<List<GitLabDiff>> getFiles() throws ApiRequestException {
        GitLabCommitService service = ServiceFactory.get(GitLabCommitService.class, false);
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getCommitDiff(projectId, mSha, 1, 100)
                        .map(ApiHelpers::throwOnFailure));
    }
}
