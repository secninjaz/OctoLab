package com.gl4a.fragment;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.gitlab.model.GitLabEvent;
import com.gl4a.gitlab.model.GitLabProject;
import com.gl4a.gitlab.model.GitLabPage;

import android.os.Bundle;

import com.gl4a.ServiceFactory;

import io.reactivex.Single;
import retrofit2.Response;

public class RepositoryEventListFragment extends EventListFragment {
    private GitLabProject mRepository;

    public static RepositoryEventListFragment newInstance(GitLabProject repository) {
        RepositoryEventListFragment f = new RepositoryEventListFragment();
        Bundle args = new Bundle();
        args.putParcelable("repository", repository);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = getArguments().getParcelable("repository");
    }

    @Override
    protected Single<Response<GitLabPage<GitLabEvent>>> loadRawPage(int page, boolean bypassCache) {
        GitLabUserService service = ServiceFactory.get(GitLabUserService.class, bypassCache);
        return service.getProjectEvents(mRepository.id(), page, 30)
                .map(response -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        return retrofit2.Response.<GitLabPage<GitLabEvent>>error(
                                response.errorBody(), response.raw());
                    }
                    return retrofit2.Response.success(com.gl4a.utils.ApiHelpers.toPage(response));
                });
    }
}
