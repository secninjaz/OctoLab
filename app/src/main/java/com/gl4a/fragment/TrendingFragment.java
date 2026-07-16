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

import android.os.Bundle;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.adapter.TrendAdapter;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.model.Trend;
import com.gl4a.utils.ApiHelpers;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;

public class TrendingFragment extends ListDataBaseFragment<Trend> implements
        RootAdapter.OnItemClickListener<Trend> {
    public static final String TYPE_DAILY = "daily";
    public static final String TYPE_WEEKLY = "weekly";
    public static final String TYPE_MONTHLY = "monthly";

    private String mType;
    private @StringRes int mStarsTemplate;

    public static TrendingFragment newInstance(String type) {
        if (type == null) {
            return null;
        }

        TrendingFragment f = new TrendingFragment();
        Bundle args = new Bundle();
        args.putString("type", type);
        switch (type) {
            case TYPE_DAILY: args.putInt("stars_template", R.string.trend_stars_today); break;
            case TYPE_WEEKLY: args.putInt("stars_template", R.string.trend_stars_week); break;
            case TYPE_MONTHLY: args.putInt("stars_template", R.string.trend_stars_month); break;
            default: throw new IllegalArgumentException();
        }
        f.setArguments(args);

        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mType = getArguments().getString("type");
        mStarsTemplate = getArguments().getInt("stars_template", 0);
    }

    @Override
    protected RootAdapter<Trend, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        TrendAdapter adapter = new TrendAdapter(getActivity(), mStarsTemplate);
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_trends_found;
    }

    @Override
    public void onItemClick(Trend trend) {
        String owner = trend.getRepoOwner();
        String name = trend.getRepoName();
        if (owner != null && name != null) {
            startActivity(RepositoryActivity.makeIntent(getActivity(), owner, name));
        }
    }

    @Override
    protected Single<List<Trend>> onCreateDataSingle(boolean bypassCache) {
        // GitLab has no native trending API. As the closest equivalent, fetch the
        // 25 most recently active projects the authenticated user has access to,
        // ordered by last_activity_at descending. All three tab types (daily, weekly,
        // monthly) use the same endpoint — the time-window label is cosmetic only
        // since GitLab does not support filtering projects by activity window.
        GitLabProjectService service = ServiceFactory.get(GitLabProjectService.class, bypassCache);
        return service.listProjects(
                        true,               // membership=true: only projects the user belongs to
                        1,                  // page
                        25,                 // per_page
                        "last_activity_at", // order_by
                        "desc",             // sort
                        null,               // visibility: all
                        true                // simple: reduce response payload
                )
                .map(ApiHelpers::throwOnFailure)
                .map(projects -> {
                    List<Trend> trends = new ArrayList<>();
                    for (GitLabProject p : projects) {
                        String owner = null;
                        String repo = null;
                        if (p.pathWithNamespace != null) {
                            int slash = p.pathWithNamespace.lastIndexOf('/');
                            if (slash >= 0) {
                                owner = p.pathWithNamespace.substring(0, slash);
                                repo = p.pathWithNamespace.substring(slash + 1);
                            }
                        }
                        if (owner == null) {
                            owner = p.pathWithNamespace != null ? p.pathWithNamespace : "";
                            repo = p.name != null ? p.name : "";
                        }
                        trends.add(new Trend(
                                owner,
                                repo,
                                p.description,
                                p.language(),   // GitLabProject.language() returns null — acceptable
                                p.starCount,
                                0,              // newStars: not available from GitLab API
                                p.forksCount
                        ));
                    }
                    return trends;
                });
    }
}
