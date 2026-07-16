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

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.adapter.EventAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.service.GitLabProjectService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

/**
 * Base fragment for displaying GitLab events (activity feed).
 *
 * NOTE: The original GitHub event handling logic (payload type dispatch, context menus, etc.)
 * has been stubbed because GitHub SDK payload types are no longer available and GitLab's
 * event structure is fundamentally different. Subclasses provide the data source; full
 * event-type handling is pending GitLab-native implementation.
 */
public abstract class EventListFragment extends PagedDataBaseFragment<GitLabEvent> {

    private EventAdapter mAdapter;
    private final Map<Long, String> mProjectPaths = new HashMap<>();
    private final Set<Long> mFetchingIds = new HashSet<>();

    @Override
    protected RootAdapter<GitLabEvent, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        mAdapter = new EventAdapter(getActivity());
        return mAdapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_events_found;
    }

    @Override
    public void onItemClick(GitLabEvent event) {
    }

    @Override
    protected void onAddData(RootAdapter<GitLabEvent, ? extends RecyclerView.ViewHolder> adapter,
            Collection<GitLabEvent> data) {
        super.onAddData(adapter, data);
        fetchMissingProjectPaths(data);
    }

    private void fetchMissingProjectPaths(Collection<GitLabEvent> events) {
        Set<Long> needed = new HashSet<>();
        for (GitLabEvent e : events) {
            if (e.projectId > 0 && !mProjectPaths.containsKey(e.projectId)
                    && !mFetchingIds.contains(e.projectId)) {
                needed.add(e.projectId);
            }
        }
        if (needed.isEmpty()) return;
        mFetchingIds.addAll(needed);

        List<Single<Response<GitLabProject>>> calls = new ArrayList<>();
        GitLabProjectService svc = ServiceFactory.get(GitLabProjectService.class, false);
        for (long id : needed) {
            calls.add(svc.getProject(id));
        }
        Single.merge(calls)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    response -> {
                        if (response.isSuccessful() && response.body() != null) {
                            GitLabProject p = response.body();
                            String path = p.pathWithNamespace != null
                                    ? p.pathWithNamespace : p.name;
                            if (path != null) mProjectPaths.put(p.id, path);
                        }
                        if (mAdapter != null) mAdapter.setProjectPaths(mProjectPaths);
                    },
                    error -> { /* best-effort — silently skip */ }
                );
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadPage(int page, boolean bypassCache) {
        return loadRawPage(page, bypassCache);
    }

    protected abstract Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache);
}
