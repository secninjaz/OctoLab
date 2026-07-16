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
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabPage;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.UserActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.UserAdapter;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.RxUtils;

import java.net.HttpURLConnection;

import io.reactivex.Single;
import retrofit2.Response;

public class WatcherListFragment extends PagedDataBaseFragment<GitLabUser> {
    public static WatcherListFragment newInstance(String repoOwner, String repoName) {
        WatcherListFragment f = new WatcherListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        f.setArguments(args);

        return f;
    }

    // Use a loader ID that cannot collide with PagedDataBaseFragment's internal ID (0).
    private static final int ID_LOADER_WATCHING = 10;

    private String mRepoOwner;
    private String mRepoName;
    private Boolean mIsWatching;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);
        mRepoOwner = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        loadWatchingState(false);
    }

    @Override
    protected RootAdapter<GitLabUser, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new UserAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_watchers_found;
    }

    @Override
    public void onItemClick(GitLabUser user) {
        Intent intent = UserActivity.makeIntent(getActivity(), user);
        if (intent != null) {
            startActivity(intent);
        }
    }

    @Override
    public void onRefresh() {
        mIsWatching = null;
        loadWatchingState(true);
        super.onRefresh();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (Gl4Application.get().isAuthorized()) {
            MenuItem starItem = menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, "")
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
            if (mIsWatching == null) {
                starItem.setActionView(R.layout.ab_loading);
                starItem.expandActionView();
            } else if (mIsWatching) {
                starItem.setTitle(R.string.repo_unwatch_action);
            } else {
                starItem.setTitle(R.string.repo_watch_action);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == Menu.FIRST) {
            toggleWatchingState();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadWatchingState(boolean force) {
        if (!Gl4Application.get().isAuthorized()) {
            return;
        }
        // GitLab has no watch/subscribe concept — stub with false
        io.reactivex.Single.just(false)
                .compose(makeLoaderSingle(ID_LOADER_WATCHING, force))
                .subscribe(result -> {
                    mIsWatching = result;
                    getActivity().invalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    private void toggleWatchingState() {
        // GitLab has no watch/subscribe concept — stub no-op
        if (mIsWatching != null) {
            mIsWatching = !mIsWatching;
            getActivity().invalidateOptionsMenu();
        }
    }

    @Override
    protected Single<Response<GitLabPage<GitLabUser>>> loadPage(int page, boolean bypassCache) {
        // GitLab has no watchers endpoint — return empty page
        return io.reactivex.Single.just(
                retrofit2.Response.<GitLabPage<GitLabUser>>success(new com.gl4a.utils.ApiHelpers.DummyPage<>()));
    }
}