package com.gl4a.resolver;
import com.gl4a.gitlab.model.GitLabBranch;
import com.gl4a.gitlab.model.GitLabTag;
import com.gl4a.gitlab.service.GitLabProjectService;

import android.content.Intent;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import android.net.Uri;
import android.util.Pair;

import com.gl4a.ApiRequestException;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.FileViewerActivity;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.RxUtils;
import com.gl4a.utils.SingleFactory;

import java.util.Optional;
import java.util.regex.Pattern;

import io.reactivex.Single;

public class RefPathDisambiguationTask extends UrlLoadTask {
    private static final Pattern SHA1_PATTERN = Pattern.compile("[a-z0-9]{40}");

    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final String mRefAndPath;
    @VisibleForTesting
    protected final int mInitialPage;
    @VisibleForTesting
    protected final String mFragment;
    @VisibleForTesting
    protected final boolean mGoToFileViewer;

    public RefPathDisambiguationTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, String refAndPath, int initialPage) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mInitialPage = initialPage;
        mFragment = null;
        mGoToFileViewer = false;
    }

    public RefPathDisambiguationTask(FragmentActivity activity, Uri urlToResolve,
            String repoOwner, String repoName, String refAndPath, String fragment) {
        super(activity, urlToResolve);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mFragment = fragment;
        mInitialPage = -1;
        mGoToFileViewer = true;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        return resolveRefAndPath()
                .map(refAndPathOpt -> {
                    if (!refAndPathOpt.isPresent()) {
                        return Optional.empty();
                    }
                    Pair<String, String> refAndPath = refAndPathOpt.get();
                    if (mGoToFileViewer && refAndPath.second != null) {
                        // parse line numbers from fragment
                        int highlightStart = -1, highlightEnd = -1;
                        // Line numbers are encoded either in the form #L12 or #L12-14
                        if (mFragment != null && mFragment.startsWith("L")) {
                            try {
                                int dashPos = mFragment.indexOf("-L");
                                if (dashPos > 0) {
                                    highlightStart = Integer.valueOf(mFragment.substring(1, dashPos));
                                    highlightEnd = Integer.valueOf(mFragment.substring(dashPos + 2));
                                } else {
                                    highlightStart = Integer.valueOf(mFragment.substring(1));
                                }
                            } catch (NumberFormatException e) {
                                // ignore
                            }
                        }
                        return Optional.of(FileViewerActivity.makeIntentWithHighlight(mActivity,
                                mRepoOwner, mRepoName, refAndPath.first, refAndPath.second,
                                highlightStart, highlightEnd));
                    } else if (!mGoToFileViewer) {
                        return Optional.of(RepositoryActivity.makeIntent(mActivity,
                                mRepoOwner, mRepoName, refAndPath.first,
                                refAndPath.second, mInitialPage));
                    }
                    return Optional.empty();
                });
    }

    // returns ref, path
    private Single<Optional<Pair<String, String>>> resolveRefAndPath() throws ApiRequestException {
        // first check whether the path redirects to HEAD
        if (mRefAndPath.startsWith("HEAD")) {
            return Single.just(Optional.of(Pair.create("HEAD",
                    mRefAndPath.startsWith("HEAD/") ? mRefAndPath.substring(5) : null)));
        }
        // or whether the ref is a commit SHA-1
        int slashPos = mRefAndPath.indexOf('/');
        String potentialSha = slashPos > 0 ? mRefAndPath.substring(0, slashPos) : mRefAndPath;
        if (SHA1_PATTERN.matcher(potentialSha).matches()) {
            return Single.just(Optional.of(Pair.create(potentialSha,
                    slashPos > 0 ? mRefAndPath.substring(slashPos + 1) : "")));
        }

        GitLabProjectService projectService = ServiceFactory.get(GitLabProjectService.class, false);

        // then look for matching branches
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> projectService.getBranches(projectId, 1, 100)
                        .map(ApiHelpers::throwOnFailure)
                        .map(branches -> {
                            for (GitLabBranch b : branches) {
                                if (matchesUrlPath(b)) return Optional.of(b);
                            }
                            return Optional.<GitLabBranch>empty();
                        })
                        // and tags after that
                        .flatMap(result -> RxUtils.toSingleOrFallback(result, () ->
                                projectService.getTags(projectId, 1, 100)
                                        .map(ApiHelpers::throwOnFailure)
                                        .map(tags -> {
                                            for (GitLabTag tag : tags) {
                                                if (mRefAndPath.equals(tag.name) || mRefAndPath.startsWith(tag.name + "/")) {
                                                    GitLabBranch b = new GitLabBranch();
                                                    b.name = tag.name;
                                                    return Optional.of(b);
                                                }
                                            }
                                            return Optional.<GitLabBranch>empty();
                                        }))))
                .map(this::determineRefAndPathFromFoundRef);
    }

    private boolean matchesUrlPath(GitLabBranch ref) {
        return mRefAndPath.equals(ref.name()) || mRefAndPath.startsWith(ref.name() + "/");
    }

    private Optional<Pair<String, String>> determineRefAndPathFromFoundRef(Optional<GitLabBranch> foundRef) {
        return foundRef.map(ref -> {
            if (mRefAndPath.equals(ref.name())) {
                return Pair.create(ref.name(), null);
            } else {
                String refNameWithSlash = ref.name() + "/";
                return Pair.create(ref.name(), mRefAndPath.substring(refNameWithSlash.length()));
            }
        });
    }
}
