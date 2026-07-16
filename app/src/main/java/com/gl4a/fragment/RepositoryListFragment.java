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

import java.util.Collection;
import java.util.List;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RepositoryAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.utils.ApiHelpers;

import io.reactivex.Single;
import retrofit2.Response;

public class RepositoryListFragment extends PagedDataBaseFragment<GitLabProject> {
    private String mLogin;
    private String mRepoType;
    private boolean mIsOrg;
    private String mSortOrder;
    private String mSortDirection;

    public static RepositoryListFragment newInstance(String login, boolean isOrg,
            String repoType, String sortOrder, String sortDirection) {
        RepositoryListFragment f = new RepositoryListFragment();

        Bundle args = new Bundle();
        args.putString("user", login);
        args.putBoolean("is_org", isOrg);
        args.putString("repo_type", repoType);
        args.putString("sort_order", sortOrder);
        args.putString("sort_direction", sortDirection);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Bundle args = getArguments();
        mLogin = args.getString("user");
        mRepoType = args.getString("repo_type");
        mIsOrg = args.getBoolean("is_org");
        mSortOrder = args.getString("sort_order", "last_activity_at");
        mSortDirection = args.getString("sort_direction", "desc");
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new RepositoryAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_repos_found;
    }

    @Override
    protected void onAddData(RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> adapter,
            Collection<GitLabProject> repositories) {
        if ("sources".equals(mRepoType) || "forks".equals(mRepoType)) {
            for (GitLabProject project : repositories) {
                if ("sources".equals(mRepoType) && !project.isFork()) {
                    adapter.add(project);
                } else if ("forks".equals(mRepoType) && project.isFork()) {
                    adapter.add(project);
                }
            }
            adapter.notifyDataSetChanged();
        } else {
            adapter.addAll(repositories);
        }
    }

    @Override
    public void onItemClick(GitLabProject project) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), project));
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        final GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        final boolean isSelf = ApiHelpers.loginEquals(mLogin, Gl4Application.get().getAuthLogin());

        Single<Response<List<GitLabProject>>> raw;
        if (isSelf) {
            String sortOrder = mSortOrder != null ? mSortOrder : "last_activity_at";
            String sortDir = mSortDirection != null ? mSortDirection : "desc";
            if ("starred".equals(mRepoType)) {
                // Starred projects for the authenticated user
                raw = service.getStarredProjects(true, page, 25);
            } else {
                // Your Projects — all projects the user is a member of
                raw = service.listProjects(true, page, 25, sortOrder, sortDir, null, false);
            }
        } else {
            raw = service.getUserProjects(mLogin, page, 25);
        }

        return raw.map(response -> {
            if (response.isSuccessful()) {
                return Response.success(ApiHelpers.toPage(response));
            }
            return Response.<GitLabPage<GitLabProject>>error(response.errorBody(), response.raw());
        });
    }
}
