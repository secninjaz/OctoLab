/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gl4a.fragment;

import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabPage;

import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.activities.UserActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.UserAdapter;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * GitLab does not support followers/following. This fragment is stubbed to show an empty list.
 */
public class FollowersFollowingListFragment extends PagedDataBaseFragment<GitLabUser> {
    public static FollowersFollowingListFragment newInstance(String login, boolean showFollowers) {
        FollowersFollowingListFragment f = new FollowersFollowingListFragment();

        Bundle args = new Bundle();
        args.putString("user", login);
        args.putBoolean("show_followers", showFollowers);
        f.setArguments(args);

        return f;
    }

    @Override
    protected RootAdapter<GitLabUser, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new UserAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        // GitLab does not support followers/following
        return R.string.no_followers_found;
    }

    @Override
    public void onItemClick(GitLabUser user) {
        Intent intent = UserActivity.makeIntent(getActivity(), user);
        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    protected Single<Response<GitLabPage<GitLabUser>>> loadPage(int page, boolean bypassCache) {
        // GitLab does not support followers/following; return an empty page.
        return Single.just(Response.success(new GitLabPage<GitLabUser>()));
    }
}
