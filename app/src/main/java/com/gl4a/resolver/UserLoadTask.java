package com.gl4a.resolver;

import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabUserService;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.gl4a.ServiceFactory;
import com.gl4a.utils.ApiHelpers;

import java.util.List;
import java.util.Optional;

import io.reactivex.Single;

public abstract class UserLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mUserLogin;

    public UserLoadTask(FragmentActivity activity, Uri urlToResolve, String userLogin) {
        super(activity, urlToResolve);
        this.mUserLogin = userLogin;
    }

    @Override
    protected Single<Optional<Intent>> getSingle() {
        GitLabUserService userService = ServiceFactory.get(GitLabUserService.class, false);
        return userService.searchUsers(mUserLogin, 1, 1)
                .map(ApiHelpers::throwOnFailure)
                .map(users -> {
                    if (users != null && !users.isEmpty()) {
                        return Optional.of(getIntent(users.get(0)));
                    }
                    return Optional.<Intent>empty();
                });
    }

    protected abstract Intent getIntent(GitLabUser user);
}
