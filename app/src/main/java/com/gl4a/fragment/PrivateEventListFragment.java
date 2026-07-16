package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;

import com.gl4a.ServiceFactory;
import com.gl4a.utils.ApiHelpers;

import io.reactivex.Single;
import retrofit2.Response;

public class PrivateEventListFragment extends EventListFragment {
    private String mLogin;
    private String mOrganization;

    public static PrivateEventListFragment newInstance(String login, String organization) {
        PrivateEventListFragment f = new PrivateEventListFragment();
        Bundle args = new Bundle();
        args.putString("login", login);
        args.putString("org", organization);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLogin = getArguments().getString("login");
        mOrganization = getArguments().getString("org");
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        final GitLabUserService service = ServiceFactory.get(GitLabUserService.class, bypassCache);
        // GitLab has no org-events endpoint; use getCurrentUserEvents for the authenticated user's feed
        return service.getCurrentUserEvents(page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabEvent>>error(
                                response.errorBody(), response.raw());
                    }
                    return Response.success(ApiHelpers.toPage(response));
                });
    }
}
