package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.R;
import com.gl4a.fragment.EpicListFragment;
import com.gl4a.fragment.IssueListFragment;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.UiUtils;
import com.gl4a.widget.SearchView;

/**
 * A group's epics (#312): Open, Closed and All, as a project's issues, with search and the
 * issue lists' sorting (bar by comments, which GitLab doesn't sort epics by).
 */
public class EpicListActivity extends BaseFragmentPagerActivity implements
        SearchView.OnCloseListener, SearchView.OnQueryTextListener,
        MenuItem.OnActionExpandListener {
    public static Intent makeIntent(Context context, String groupPath) {
        return new Intent(context, EpicListActivity.class).putExtra("group", groupPath);
    }

    private static final int[] TITLES = new int[] {
        R.string.open, R.string.closed, R.string.all
    };
    private static final int[][] HEADER_COLOR_ATTRS = new int[][] {
        { R.attr.colorIssueOpen, R.attr.colorIssueOpenDark },
        { R.attr.colorIssueClosed, R.attr.colorIssueClosedDark },
        { androidx.appcompat.R.attr.colorPrimary, androidx.appcompat.R.attr.colorPrimaryDark }
    };
    private static final String STATE_KEY_SEARCH_QUERY = "search_query";
    private static final String STATE_KEY_SEARCH_IS_EXPANDED = "search_is_expanded";

    private String mGroupPath;
    private String mSearchQuery;
    // The query the lists show, set when it's submitted
    private String mShownQuery;
    private boolean mSearchIsExpanded;
    private SearchView mSearch;
    private final IssueListFragment.SortDrawerHelper mSortHelper =
            new IssueListFragment.SortDrawerHelper();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSearchQuery = savedInstanceState.getString(STATE_KEY_SEARCH_QUERY);
            mShownQuery = mSearchQuery;
            mSearchIsExpanded = savedInstanceState.getBoolean(STATE_KEY_SEARCH_IS_EXPANDED);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_KEY_SEARCH_QUERY, mShownQuery);
        outState.putBoolean(STATE_KEY_SEARCH_IS_EXPANDED, mSearchIsExpanded);
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mGroupPath = extras.getString("group");
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.epics);
    }

    @Nullable
    @Override
    protected String getActionBarSubtitle() {
        return mGroupPath;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TITLES;
    }

    @Override
    protected int[][] getTabHeaderColorAttrs() {
        return HEADER_COLOR_ATTRS;
    }

    @Override
    protected Fragment makeFragment(int position) {
        String state = position == 1 ? ApiHelpers.IssueState.CLOSED
                : position == 2 ? "all" : ApiHelpers.IssueState.OPEN;
        return EpicListFragment.newInstance(mGroupPath, state, mShownQuery,
                mSortHelper.getSortMode(), mSortHelper.getSortOrder());
    }

    @Override
    protected int[] getRightNavigationDrawerMenuResources() {
        return new int[] { IssueListFragment.SortDrawerHelper.getMenuResId() };
    }

    @Override
    protected int getInitialRightDrawerSelection() {
        return R.id.sort_created_desc;
    }

    @Override
    protected void onPrepareRightNavigationDrawerMenu(Menu menu) {
        super.onPrepareRightNavigationDrawerMenu(menu);
        menu.removeItem(R.id.sort_comments_desc);
        menu.removeItem(R.id.sort_comments_asc);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        super.onNavigationItemSelected(item);
        if (mSortHelper.handleItemSelection(item)) {
            invalidateFragments();
            return true;
        }
        return false;
    }

    @Override
    protected boolean fragmentNeedsRefresh(Fragment object) {
        return true;
    }

    @Override
    protected Intent navigateUp() {
        return GroupActivity.makeIntent(this, mGroupPath);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // The issue list's search and sort drawer; epics have no filters yet
        getMenuInflater().inflate(R.menu.issue_list_menu, menu);
        menu.removeItem(R.id.remove_filter);

        MenuItem searchItem = menu.findItem(R.id.search);
        searchItem.setOnActionExpandListener(this);
        mSearch = (SearchView) searchItem.getActionView();
        UiUtils.tintSearchViewIcons(mSearch);
        if (mSearchIsExpanded) {
            searchItem.expandActionView();
            mSearch.setQuery(mSearchQuery, false);
        }
        mSearch.setOnCloseListener(this);
        mSearch.setOnQueryTextListener(this);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.overflow) {
            toggleRightSideDrawer();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        mSearchIsExpanded = true;
        return true;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        return onClose();
    }

    @Override
    public boolean onClose() {
        mSearchIsExpanded = false;
        mSearchQuery = null;
        showQuery(null);
        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        mSearchQuery = query;
        showQuery(query);
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (mSearch.isExpanded()) {
            mSearchQuery = newText;
        }
        return false;
    }

    private void showQuery(@Nullable String query) {
        String shown = query == null || query.trim().isEmpty() ? null : query.trim();
        if (shown == null ? mShownQuery == null : shown.equals(mShownQuery)) return;
        mShownQuery = shown;
        invalidateFragments();
    }
}
