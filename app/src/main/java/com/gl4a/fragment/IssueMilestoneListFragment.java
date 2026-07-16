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
import com.gl4a.gitlab.service.GitLabIssueService;
import com.gl4a.gitlab.model.GitLabMilestone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.IssueMilestoneEditActivity;
import com.gl4a.adapter.MilestoneAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.List;

import io.reactivex.Single;

public class IssueMilestoneListFragment extends ListDataBaseFragment<GitLabMilestone> implements
        RootAdapter.OnItemClickListener<GitLabMilestone> {
    private String mRepoOwner;
    private String mRepoName;
    private boolean mShowClosed;
    private boolean mFromPullRequest;

    private final ActivityResultLauncher<Intent> mEditMilestoneLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultHelpers.ActivityResultSuccessCallback(() -> {
                onRefresh();
                getActivity().setResult(Activity.RESULT_OK);
            })
    );

    public static IssueMilestoneListFragment newInstance(String repoOwner, String repoName,
            boolean showClosed, boolean fromPullRequest) {
        IssueMilestoneListFragment f = new IssueMilestoneListFragment();

        Bundle args = new Bundle();
        args.putString("owner", repoOwner);
        args.putString("repo", repoName);
        args.putBoolean("closed", showClosed);
        args.putBoolean("from_pr", fromPullRequest);
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mRepoOwner = args.getString("owner");
        mRepoName = args.getString("repo");
        mShowClosed = args.getBoolean("closed");
        mFromPullRequest = args.getBoolean("from_pr", false);
    }

    @Override
    protected RootAdapter<GitLabMilestone, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        MilestoneAdapter adapter = new MilestoneAdapter(getActivity());
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return mShowClosed
                ? R.string.no_closed_milestones_found
                : R.string.no_open_milestones_found;
    }

    @Override
    public void onItemClick(GitLabMilestone milestone) {
        Intent intent = IssueMilestoneEditActivity.makeEditIntent(
                getActivity(), mRepoOwner, mRepoName, milestone, mFromPullRequest);
        mEditMilestoneLauncher.launch(intent);
    }

    @Override
    protected Single<List<GitLabMilestone>> onCreateDataSingle(boolean bypassCache) {
        final GitLabIssueService service = ServiceFactory.get(GitLabIssueService.class, bypassCache);
        final String targetState = mShowClosed ? "closed" : "active";

        return SingleFactory.getProjectId(mRepoOwner, mRepoName)
                .flatMap(projectId -> service.getMilestones(projectId, targetState, 1, 100)
                        .map(ApiHelpers::throwOnFailure));
    }
}
