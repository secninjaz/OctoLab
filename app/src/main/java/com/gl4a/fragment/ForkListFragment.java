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
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.Collections;
import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class ForkListFragment extends PagedDataBaseFragment<GitLabProject> {
    private String mRepoOwner;
    private String mRepoName;

    public static ForkListFragment newInstance(String repoOwner, String repoName) {
        ForkListFragment f = new ForkListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepoOwner = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        return new RepositoryAdapter(getActivity());
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_forks_found;
    }

    @Override
    public void onItemClick(GitLabProject repo) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), repo));
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        final GitLabProjectService service =
                ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getForks(projectId, page, 25))
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        // Do NOT use response.raw() here — the OkHttp response body may already
                        // be closed on non-2xx responses. Propagate via ApiRequestException instead.
                        throw new com.gl4a.ApiRequestException(response);
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }
}
