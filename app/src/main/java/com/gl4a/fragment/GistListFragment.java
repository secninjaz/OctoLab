package com.gl4a.fragment;

import android.os.Bundle;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GistActivity;
import com.gl4a.adapter.GistAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.model.GitLabSnippet;
import com.gl4a.gitlab.service.GitLabSnippetService;
import com.gl4a.utils.ApiHelpers;

import java.util.List;

import io.reactivex.Single;
import retrofit2.Response;

public class GistListFragment extends PagedDataBaseFragment<GitLabSnippet> implements
        RootAdapter.OnItemClickListener<GitLabSnippet> {

    public static GistListFragment newInstance(String userLogin, long userId) {
        Bundle args = new Bundle();
        args.putString("user", userLogin);
        args.putLong("user_id", userId);

        GistListFragment f = new GistListFragment();
        f.setArguments(args);
        return f;
    }

    private String mUserLogin;
    private long mUserId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserLogin = getArguments().getString("user");
        mUserId = getArguments().getLong("user_id", -1L);
    }

    @Override
    protected Single<Response<GitLabPage<GitLabSnippet>>> loadPage(int page, boolean bypassCache) {
        final GitLabSnippetService service = ServiceFactory.get(GitLabSnippetService.class, bypassCache);
        Single<Response<List<GitLabSnippet>>> call = mUserId > 0
                ? service.getUserSnippets(mUserId, page, 25)
                : service.listMySnippets(page, 25);
        return call.map(response -> {
            if (response.isSuccessful()) {
                return Response.success(ApiHelpers.toPage(response));
            }
            // GET /users/:id/snippets requires admin access; treat 403/404 as empty list.
            int code = response.code();
            if (mUserId > 0 && (code == 403 || code == 404)) {
                return Response.success(new GitLabPage<>(
                        java.util.Collections.emptyList(), page, 0, 1, 0));
            }
            return Response.<GitLabPage<GitLabSnippet>>error(response.errorBody(), response.raw());
        });
    }

    @Override
    protected RootAdapter<GitLabSnippet, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        GistAdapter adapter = new GistAdapter(getActivity(), mUserLogin);
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_gists_found;
    }

    @Override
    public void onItemClick(GitLabSnippet snippet) {
        startActivity(GistActivity.makeIntent(getActivity(), snippet.id()));
    }
}
