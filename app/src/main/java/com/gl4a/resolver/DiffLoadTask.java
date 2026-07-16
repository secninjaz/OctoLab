package com.gl4a.resolver;
import com.gl4a.gitlab.model.GitLabDiff;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.activities.FileViewerActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.FileUtils;
import com.gl4a.utils.RxUtils;

import java.util.List;
import java.util.Optional;

import io.reactivex.Single;

public abstract class DiffLoadTask extends UrlLoadTask {
    protected final String mRepoOwner;
    protected final String mRepoName;
    protected final DiffHighlightId mDiffId;

    public DiffLoadTask(FragmentActivity activity, Uri urlToResolve, String repoOwner,
            String repoName, DiffHighlightId diffId) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mDiffId = diffId;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        Single<Optional<GitLabDiff>> fileSingle = getFiles()
                .compose(RxUtils.filterAndMapToFirst(
                        f -> ApiHelpers.sha256Of(f.filename()).equalsIgnoreCase(mDiffId.fileHash)));
        return Single.zip(getSha(), fileSingle, (sha, fileOpt) -> {
            final Intent intent;
            GitLabDiff file = fileOpt.orElse(null);
            if (file != null && FileUtils.isImage(file.filename())) {
                intent = FileViewerActivity.makeIntent(mActivity, mRepoOwner, mRepoName,
                        sha, file.filename());
            } else if (file != null) {
                intent = getLaunchIntent(sha, file, mDiffId);
            } else {
                intent = getFallbackIntent(sha);
            }
            return Optional.of(intent);
        });
    }

    protected abstract Single<List<GitLabDiff>> getFiles();
    protected abstract Single<String> getSha();
    protected abstract @NonNull Intent getLaunchIntent(String sha, @NonNull GitLabDiff file, DiffHighlightId diffId);
    protected abstract @NonNull Intent getFallbackIntent(String sha);
}
