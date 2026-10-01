package com.gl4a.resolver;

import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.CompareActivity;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.Optional;

import io.reactivex.Single;

/**
 * Opens a system note's "Compare with previous version" link
 * (/-/merge_requests/:iid/diffs?diff_id=<version>&start_sha=<previous head>) as the diff between
 * the two MR versions, like GitLab web, instead of the whole MR diff (#184). Uses the compare
 * screen, which shows the same file list and diff view as a commit.
 */
public class MergeRequestVersionCompareLoadTask extends UrlLoadTask {
    private final String mOwner;
    private final String mRepo;
    private final int mMergeRequestIid;
    private final long mVersionId;
    private final String mStartSha;

    public MergeRequestVersionCompareLoadTask(FragmentActivity activity, Uri urlToResolve,
            String owner, String repo, int mergeRequestIid, long versionId, String startSha) {
        super(activity, urlToResolve);
        mOwner = owner;
        mRepo = repo;
        mMergeRequestIid = mergeRequestIid;
        mVersionId = versionId;
        mStartSha = startSha;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        GitLabMergeRequestService service =
                ServiceFactory.get(GitLabMergeRequestService.class, false);
        return SingleFactory.getProjectId(mOwner, mRepo)
                .flatMap(projectId -> service.getVersion(projectId, mMergeRequestIid, mVersionId))
                .map(ApiHelpers::throwOnFailure)
                .map(version -> {
                    // Without start_sha, show the version against its base, as GitLab does.
                    String from = mStartSha != null ? mStartSha : version.baseCommitSha;
                    if (from == null || version.headCommitSha == null) {
                        return Optional.<Intent>empty();
                    }
                    return Optional.of(CompareActivity.makeIntent(mActivity, mOwner, mRepo,
                            from, version.headCommitSha));
                });
    }
}
