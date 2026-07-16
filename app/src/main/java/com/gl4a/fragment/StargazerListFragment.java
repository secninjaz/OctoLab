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
import okhttp3.Headers;
import com.gl4a.gitlab.model.GitLabStarrer;
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

import io.reactivex.Single;
import retrofit2.Response;

public class StargazerListFragment extends PagedDataBaseFragment<GitLabUser> {
    public static StargazerListFragment newInstance(String repoOwner, String repoName) {
        StargazerListFragment f = new StargazerListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        f.setArguments(args);

        return f;
    }

    // Use a loader ID that cannot collide with PagedDataBaseFragment's internal ID (0).
    private static final int ID_LOADER_STARRING = 10;

    private String mRepoOwner;
    private String mRepoName;
    private Boolean mIsStarring;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);
        mRepoOwner = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        loadStarringState(false);
    }

    private static int safeInt(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override
    protected RootAdapter<GitLabUser, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new UserAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_stargazers_found;
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
        mIsStarring = null;
        loadStarringState(true);
        super.onRefresh();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (Gl4Application.get().isAuthorized()) {
            MenuItem starItem = menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, "")
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
            if (mIsStarring == null) {
                starItem.setActionView(R.layout.ab_loading);
                starItem.expandActionView();
            } else if (mIsStarring) {
                starItem.setTitle(R.string.repo_unstar_action);
            } else {
                starItem.setTitle(R.string.repo_star_action);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == Menu.FIRST) {
            toggleStarringState();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabUser>>> loadPage(int page, boolean bypassCache) {
        final GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return com.gl4a.utils.SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getStarrers(projectId, page, 25)
                        .map(response -> {
                            if (!response.isSuccessful() || response.body() == null) {
                                return retrofit2.Response.<GitLabPage<GitLabUser>>success(new ApiHelpers.DummyPage<>());
                            }
                            // API returns [{starred_since, user{}}] — extract user objects
                            java.util.List<GitLabUser> users = new java.util.ArrayList<>();
                            for (GitLabStarrer starrer : response.body()) {
                                if (starrer != null && starrer.user() != null) users.add(starrer.user());
                            }
                            // Read pagination headers manually
                            okhttp3.Headers hdrs = response.headers();
                            int pg  = safeInt(hdrs.get("X-Page"), 1);
                            int nx  = safeInt(hdrs.get("X-Next-Page"), 0);
                            int tot = safeInt(hdrs.get("X-Total-Pages"), 1);
                            int ttl = safeInt(hdrs.get("X-Total"), users.size());
                            return retrofit2.Response.<GitLabPage<GitLabUser>>success(
                                new GitLabPage<>(users, pg, nx, tot, ttl));
                        }));
    }

    private void loadStarringState(boolean force) {
        if (!Gl4Application.get().isAuthorized()) {
            return;
        }
        // GitLab: check star status via project info (stub with false)
        io.reactivex.Single.just(false)
                .compose(makeLoaderSingle(ID_LOADER_STARRING, force))
                .subscribe(result -> {
                    mIsStarring = result;
                    getActivity().invalidateOptionsMenu();
                }, this::handleLoadFailure);
    }

    private void toggleStarringState() {
        final GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, false);
        Single<Boolean> responseSingle = com.gl4a.utils.SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> mIsStarring
                        ? service.unstarProject(projectId).map(r -> {
                            // 404 = not starred (already unstarred), treat as success
                            return false;
                          }).onErrorReturn(e -> false)
                        : service.starProject(projectId).map(r -> {
                            // 304 = already starred, treat as success
                            return true;
                          }).onErrorReturn(e -> true));
        responseSingle.compose(RxUtils::doInBackground)
                .subscribe(result -> {
                    if (mIsStarring != null) {
                        mIsStarring = result;
                        getActivity().invalidateOptionsMenu();
                    }
                }, error -> {
                    handleActionFailure("Updating repo starring state failed", error);
                });
    }
}