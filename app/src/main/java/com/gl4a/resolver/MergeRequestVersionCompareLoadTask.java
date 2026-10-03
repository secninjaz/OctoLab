package com.gl4a.resolver;

import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.activities.MergeRequestVersionActivity;
import com.gl4a.gitlab.model.GitLabMergeRequestVersion;
import com.gl4a.gitlab.service.GitLabMergeRequestService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.Optional;

import io.reactivex.Single;

/**
 * Opens a system note's "Compare with previous version" link
 * (/-/merge_requests/:iid/diffs?diff_id=<version>&start_sha=<previous head>) as the diff between
 * the two MR versions, like GitLab web, instead of the whole MR diff (#184): the commit screen's
 * file list and diffs, titled "Version 6 compared with version 5" (#198).
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
                .flatMap(projectId -> service.getVersions(projectId, mMergeRequestIid, 1, 100))
                .map(ApiHelpers::throwOnFailure)
                .map(versions -> {
                    // Newest first; GitLab web numbers them from the first push (version 1)
                    GitLabMergeRequestVersion version = null;
                    int number = 0;
                    int previousNumber = 0;
                    for (int i = 0; i < versions.size(); i++) {
                        GitLabMergeRequestVersion v = versions.get(i);
                        if (v.id == mVersionId) {
                            version = v;
                            number = versions.size() - i;
                        } else if (mStartSha != null && mStartSha.equals(v.headCommitSha)) {
                            previousNumber = versions.size() - i;
                        }
                    }
                    if (version == null || version.headCommitSha == null) {
                        return Optional.<Intent>empty();
                    }
                    if (mStartSha != null && previousNumber == 0 && number > 1) {
                        previousNumber = number - 1;
                    }
                    // Without start_sha, show the version against its base, as GitLab does.
                    String from = mStartSha != null ? mStartSha : version.baseCommitSha;
                    if (from == null) {
                        return Optional.<Intent>empty();
                    }
                    // The version's own changes on the commit screen, no commit list first (#198)
                    return Optional.of(MergeRequestVersionActivity.makeIntent(mActivity, mOwner,
                            mRepo, mMergeRequestIid, from, version.headCommitSha, number,
                            mStartSha != null ? previousNumber : 0));
                });
    }
}
