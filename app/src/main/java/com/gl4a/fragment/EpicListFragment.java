package com.gl4a.fragment;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.EpicActivity;
import com.gl4a.adapter.RepositoryIssueAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabIssue;
import com.gl4a.gitlab.model.GitLabPage;
import com.gl4a.gitlab.service.GitLabEpicService;
import com.gl4a.utils.ApiHelpers;

import io.reactivex.Single;
import retrofit2.Response;

/**
 * A group's epics in one state (#312), its subgroups' included as on GitLab web, in the issue
 * list's rows.
 */
public class EpicListFragment extends PagedDataBaseFragment<GitLabIssue> {
    public static EpicListFragment newInstance(String groupPath, String state, String query,
            String sortMode, String order) {
        EpicListFragment f = new EpicListFragment();
        Bundle args = new Bundle();
        args.putString("sortmode", sortMode);
        args.putString("order", order);
        args.putString("group", groupPath);
        args.putString("state", state);
        args.putString("query", query);
        f.setArguments(args);
        return f;
    }

    private String mGroupPath;
    private String mState;
    private String mQuery;
    private String mSortMode;
    private String mOrder;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        mGroupPath = args.getString("group");
        mState = args.getString("state");
        mQuery = args.getString("query");
        mSortMode = args.getString("sortmode");
        mOrder = args.getString("order");
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (ApiHelpers.IssueState.CLOSED.equals(mState)) {
            setHighlightColors(R.attr.colorIssueClosed, R.attr.colorIssueClosedDark);
        } else {
            setHighlightColors(R.attr.colorIssueOpen, R.attr.colorIssueOpenDark);
        }
    }

    @Override
    protected RootAdapter<GitLabIssue, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        RepositoryIssueAdapter adapter = new RepositoryIssueAdapter(getActivity());
        // A subgroup's epic says where it's from; this group's don't need to
        adapter.setHomePath(mGroupPath);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return mQuery != null ? R.string.no_search_epics_found : R.string.no_epics_found;
    }

    @Override
    protected Single<Response<GitLabPage<GitLabIssue>>> loadPage(int page, boolean bypassCache) {
        return ServiceFactory.get(GitLabEpicService.class, bypassCache)
                .listEpics(Uri.encode(mGroupPath), mState, mQuery, mSortMode, mOrder, page, 25)
                .map(response -> response.isSuccessful()
                        ? Response.success(ApiHelpers.toPage(response))
                        : Response.<GitLabPage<GitLabIssue>>error(response.errorBody(),
                                response.raw()));
    }

    @Override
    public void onItemClick(GitLabIssue epic) {
        android.content.Intent intent =
                EpicActivity.makeIntent(getActivity(), epic.groupPath(), epic.number());
        if (intent != null) startActivity(intent);
    }
}
