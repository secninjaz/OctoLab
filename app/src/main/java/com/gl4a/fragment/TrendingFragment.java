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
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.RepositoryActivity;
import com.gl4a.adapter.RepositoryAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;
import com.gl4a.utils.ApiHelpers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * Explore: GitLab has no trending API, so each tab lists all projects the user can see that were
 * active in its window (a day, a week, a month, a year), most-starred first, in the same rows as
 * the Projects section (#228). "Active" is GitLab's last_activity_at: pushes, issues, merge
 * requests, comments and other project events; stars don't count.
 */
public class TrendingFragment extends PagedDataBaseFragment<GitLabProject> {
    public static final String TYPE_DAILY = "daily";
    public static final String TYPE_WEEKLY = "weekly";
    public static final String TYPE_MONTHLY = "monthly";
    public static final String TYPE_YEARLY = "yearly";

    private int mDays;

    public static TrendingFragment newInstance(String type) {
        if (type == null) {
            return null;
        }
        int days;
        switch (type) {
            case TYPE_DAILY: days = 1; break;
            case TYPE_WEEKLY: days = 7; break;
            case TYPE_MONTHLY: days = 30; break;
            case TYPE_YEARLY: days = 365; break;
            default: throw new IllegalArgumentException();
        }
        TrendingFragment f = new TrendingFragment();
        Bundle args = new Bundle();
        args.putInt("days", days);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDays = getArguments().getInt("days", 1);
    }

    @Override
    protected RootAdapter<GitLabProject, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        RepositoryAdapter adapter = new RepositoryAdapter(getActivity());
        adapter.showViewerRoles(); // your role in each project, as in Groups (#233)
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_trends_found;
    }

    @Override
    public void onItemClick(GitLabProject project) {
        startActivity(RepositoryActivity.makeIntent(getActivity(), project));
    }

    @Override
    protected Single<Response<GitLabPage<GitLabProject>>> loadPage(int page, boolean bypassCache) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        Map<String, String> params = new HashMap<>();
        params.put("last_activity_after",
                iso.format(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(mDays))));
        params.put("order_by", "star_count");
        params.put("sort", "desc");
        return ServiceFactory.get(GitLabProjectService.class, bypassCache)
                .listProjectsWithParams(params, page, 25)
                .map(response -> response.isSuccessful()
                        ? Response.success(ApiHelpers.toPage(response))
                        : Response.<GitLabPage<GitLabProject>>error(response.errorBody(),
                                response.raw()));
    }
}
