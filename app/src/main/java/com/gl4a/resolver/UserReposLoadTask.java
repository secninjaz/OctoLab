package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabUser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.activities.RepositoryListActivity;
import com.gl4a.fragment.RepositoryListContainerFragment;

public class UserReposLoadTask extends UserLoadTask {
    @VisibleForTesting
    protected final boolean mShowStars;

    public UserReposLoadTask(FragmentActivity activity, Uri urlToResolve,
            String userLogin, boolean showStars) {
        super(activity, urlToResolve, userLogin);
        mShowStars = showStars;
    }

    @Override
    protected Intent getIntent(GitLabUser user) {
        // GitLab does not expose an org/bot type field; treat all users as regular users
        String filter = mShowStars
                ? RepositoryListContainerFragment.FILTER_TYPE_STARRED
                : null;
        return RepositoryListActivity.makeIntent(mActivity, user.login(), false, filter);
    }
}
