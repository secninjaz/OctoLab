package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabUser;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.activities.FollowerFollowingListActivity;
import com.gl4a.activities.UserActivity;

public class UserFollowersLoadTask extends UserLoadTask {
    @VisibleForTesting
    protected final boolean mShowFollowers;

    public UserFollowersLoadTask(FragmentActivity activity, Uri urlToResolve,
            String userLogin, boolean showFollowers) {
        super(activity, urlToResolve, userLogin);
        mShowFollowers = showFollowers;
    }

    @Override
    protected Intent getIntent(GitLabUser user) {
        // GitLab has no follower/following concept — always go to the user activity
        return UserActivity.makeIntent(mActivity, user);
    }
}
