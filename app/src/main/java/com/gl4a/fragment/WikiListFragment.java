package com.gl4a.fragment;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.WikiActivity;
import com.gl4a.adapter.CommonFeedAdapter;
import com.gl4a.adapter.RootAdapter;
import com.gl4a.gitlab.model.GitLabWikiPage;
import com.gl4a.gitlab.service.GitLabWikiService;
import com.gl4a.model.Feed;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.SingleFactory;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;

public class WikiListFragment extends ListDataBaseFragment<Feed> implements
        RootAdapter.OnItemClickListener<Feed> {
    private String mUserLogin;
    private String mRepoName;
    private String mInitialPage;

    public static WikiListFragment newInstance(String owner, String repo, String initialPage) {
        WikiListFragment f = new WikiListFragment();
        Bundle args = new Bundle();
        args.putString("owner", owner);
        args.putString("repo", repo);
        args.putString("initial_page", initialPage);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserLogin = getArguments().getString("owner");
        mRepoName = getArguments().getString("repo");
        mInitialPage = getArguments().getString("initial_page");
        getArguments().remove("initial_page");
    }

    @Override
    protected Single<List<Feed>> onCreateDataSingle(boolean bypassCache) {
        final GitLabWikiService wikiService =
                ServiceFactory.get(GitLabWikiService.class, bypassCache);
        return SingleFactory.getProjectId(mUserLogin, mRepoName)
                .flatMap(projectId -> wikiService.listWikiPages(projectId, false)
                        .map(ApiHelpers::throwOnFailure)
                        .map(pages -> {
                            List<Feed> feeds = new ArrayList<>();
                            for (GitLabWikiPage page : pages) {
                                Feed feed = new Feed();
                                feed.setId(page.slug());
                                feed.setTitle(page.title());
                                feeds.add(feed);
                            }
                            return feeds;
                        }));
    }

    @Override
    protected RootAdapter<Feed, ? extends RecyclerView.ViewHolder> onCreateAdapter() {
        CommonFeedAdapter adapter = new CommonFeedAdapter(getActivity(), false);
        adapter.setOnItemClickListener(this);
        return adapter;
    }

    @Override
    protected int getEmptyTextResId() {
        return R.string.no_wiki_updates_found;
    }

    @Override
    public void onItemClick(Feed feed) {
        openViewer(feed);
    }

    @Override
    protected void onAddData(RootAdapter<Feed, ?> adapter, List<Feed> data) {
        super.onAddData(adapter, data);

        if (mInitialPage != null) {
            for (Feed feed : data) {
                if (mInitialPage.equals(feed.getId())) {
                    openViewer(feed);
                    break;
                }
            }
            mInitialPage = null;
        }
    }

    private void openViewer(Feed feed) {
        startActivity(WikiActivity.makeIntent(getActivity(), mUserLogin, mRepoName, feed));
    }
}
