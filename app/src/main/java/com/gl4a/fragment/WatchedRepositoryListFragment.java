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
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RepositoryAdapter;
import com.gl4a.adapter.RootAdapter;

import io.reactivex.Single;
import retrofit2.Response;

public class WatchedRepositoryListFragment extends PagedDataBaseFragment<GitLabProject> {
    public static WatchedRepositoryListFragment newInstance(String login) {
        WatchedRepositoryListFragment f = new WatchedRepositoryListFragment();

        Bundle args = new Bundle();
        args.putString("user", login);
        f.setArguments(args);

        return f;
    }

    private String mLogin;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLogin = getArguments().getString("user");
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new RepositoryAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_watched_repos_found;
    }

    @Override
    public void onItemClick(GitLabProject repository) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), repository));
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        final GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return service.getWatchedProjects(true, page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return retrofit2.Response.<GitLabPage<GitLabProject>>success(new com.gl4a.utils.ApiHelpers.DummyPage<>());
                    }
                    GitLabPage<GitLabProject> resultPage = new GitLabPage<>(
                            response.body(), page, 0, 1, response.body().size());
                    return retrofit2.Response.<GitLabPage<GitLabProject>>success(resultPage);
                });
    }
}