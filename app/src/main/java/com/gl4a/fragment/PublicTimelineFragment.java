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
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;

import com.gl4a.ServiceFactory;
import com.gl4a.utils.ApiHelpers;

import io.reactivex.Single;
import retrofit2.Response;

public class PublicTimelineFragment extends EventListFragment {
    public static PublicTimelineFragment newInstance() {
        PublicTimelineFragment f = new PublicTimelineFragment();
        f.setArguments(new Bundle());
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        final GitLabUserService service = ServiceFactory.get(GitLabUserService.class, bypassCache);
        // GitLab API v4 does not expose a public unauthenticated event timeline.
        // listAllEvents() calls GET /events — the broadest activity stream available
        // to an authenticated user. This is distinct from PrivateEventListFragment,
        // which uses getCurrentUserEvents() for the user's personal activity feed.
        // Both map to the same endpoint today; listAllEvents() is kept as a separate
        // method so this screen can be updated independently when a distinct GitLab
        // public-events API becomes available.
        return service.listAllEvents(page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabEvent>>error(
                                response.errorBody(), response.raw());
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }
}