package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.utils.SingleFactory;

import android.os.Bundle;

import com.gl4a.ServiceFactory;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class PublicEventListFragment extends EventListFragment {
    private String mLogin;
    private boolean mIsOrganization;

    public static PublicEventListFragment newInstance(GitLabUser user) {
        PublicEventListFragment f = new PublicEventListFragment();
        Bundle args = new Bundle();
        args.putString("login", user.login());
        args.putLong("user_id", user.id());
        args.putBoolean("org", !"user".equals(user.type()));
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLogin = getArguments().getString("login");
        mIsOrganization = getArguments().getBoolean("org");
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        final GitLabUserService service = ServiceFactory.get(GitLabUserService.class, bypassCache);
        long userId = getArguments().getLong("user_id", 0L);
        // GitLab provides per-user events; use getUserEvents with the stored user id
        return service.getUserEvents(userId, page, 25)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return Response.<GitLabPage<GitLabEvent>>error(
                                response.errorBody(), response.raw());
                    }
                    // Use toPage() to read X-Next-Page header — enables infinite scroll.
                    // The previous manual construction hardcoded nextPage=0 which
                    // prevented any page beyond the first from loading.
                    return Response.success(com.gl4a.utils.ApiHelpers.toPage(response));
                });
    }
}
