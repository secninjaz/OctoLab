package com.gl4a.activities.home;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import android.view.Menu;
import android.view.MenuItem;

import com.gl4a.R;
import com.gl4a.fragment.IssueListFragment;
import com.gl4a.utils.ApiHelpers;

public class IssueListFactory extends FragmentFactory {
    private static final String STATE_KEY_SHOWING_CLOSED = "issue:showing_closed";

    // Issues: Created / Assigned / Mentioned / Participating
    private static final int[] ISSUE_TAB_TITLES = new int[] {
            R.string.created, R.string.assigned, R.string.mentioned, R.string.participating
    };
    // MRs: Created / Assigned / Reviews / Mentioned  (Reviews = scope=reviews_for_me)
    private static final int[] MR_TAB_TITLES = new int[] {
            R.string.created, R.string.assigned, R.string.reviews, R.string.mentioned
    };

    private boolean mShowingClosed;
    private final String mLogin;
    private final boolean mIsPullRequest;
    private final IssueListFragment.SortDrawerHelper mDrawerHelper =
            new IssueListFragment.SortDrawerHelper();
    private int[] mHeaderColorAttrs;
    private SharedPreferences mPrefs;

    public IssueListFactory(HomeActivity activity, String userLogin, boolean pr,
            SharedPreferences prefs) {
        super(activity);
        mLogin = userLogin;
        mShowingClosed = false;
        mIsPullRequest = pr;
        mPrefs = prefs;

        String lastOrder = mPrefs.getString(getSortOrderPrefKey(), null);
        String lastDir = mPrefs.getString(getSortDirPrefKey(), null);
        if (lastOrder != null && lastDir != null) {
            mDrawerHelper.setSortMode(lastOrder, lastDir);
        }
    }

    @Override
    protected @StringRes int getTitleResId() {
        if (mShowingClosed) {
            return mIsPullRequest ? R.string.pull_requests_closed : R.string.issues_closed;
        } else {
            return mIsPullRequest ? R.string.pull_requests_open : R.string.issues_open;
        }
    }

    @Override
    protected int[] getTabTitleResIds() {
        return mIsPullRequest ? MR_TAB_TITLES : ISSUE_TAB_TITLES;
    }

    @Override
    protected int[] getHeaderColorAttrs() {
        return mHeaderColorAttrs;
    }

    // Issue scopes: Created / Assigned / Mentioned / Participating
    private static final String[] ISSUE_SCOPES =
            { "created_by_me", "assigned_to_me", "mentioned", "participating" };
    // MR scopes:   Created / Assigned / Reviews / Mentioned
    private static final String[] MR_SCOPES =
            { "created_by_me", "assigned_to_me", "reviews_for_me", "mentioned" };

    @Override
    protected Fragment makeFragment(int position) {
        String[] scopes = mIsPullRequest ? MR_SCOPES : ISSUE_SCOPES;
        String scope = (position < scopes.length) ? scopes[position] : "assigned_to_me";
        String state = mShowingClosed ? ApiHelpers.IssueState.CLOSED : ApiHelpers.IssueState.OPEN;
        int emptyRes = mIsPullRequest ? R.string.no_pull_requests_found : R.string.no_issues_found;

        return IssueListFragment.newInstance(
                null,                           // no search query — using scope
                mDrawerHelper.getSortMode(),
                mDrawerHelper.getSortOrder(),
                state,
                emptyRes,
                true,                           // showRepository=true for home feed
                scope,
                mIsPullRequest);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        int resIdState = mShowingClosed ?
                R.string.issues_menu_show_open : R.string.issues_menu_show_closed;
        menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, resIdState)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        menu.add(Menu.NONE, Menu.FIRST + 1, Menu.NONE, R.string.actions)
                .setIcon(R.drawable.menu_overflow_horizontal)
                .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case Menu.FIRST:
                toggleStateFilter();
                return true;
            case Menu.FIRST + 1:
                mActivity.toggleToolDrawer();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected int[] getToolDrawerMenuResIds() {
        return new int[] { IssueListFragment.SortDrawerHelper.getMenuResId() };
    }

    @Override
    protected void prepareToolDrawerMenu(Menu menu) {
        super.prepareToolDrawerMenu(menu);
        mDrawerHelper.updateMenuCheckState(menu);
    }

    @Override
    protected boolean onDrawerItemSelected(MenuItem item) {
        if (mDrawerHelper.handleItemSelection(item)) {
            mPrefs.edit()
                    .putString(getSortOrderPrefKey(), mDrawerHelper.getSortMode())
                    .putString(getSortDirPrefKey(), mDrawerHelper.getSortOrder())
                    .apply();
            reloadIssueList();
            return true;
        }
        return false;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_KEY_SHOWING_CLOSED, mShowingClosed);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        boolean showedClosed = state.getBoolean(STATE_KEY_SHOWING_CLOSED, false);
        if (mShowingClosed != showedClosed) {
            mShowingClosed = showedClosed;
            reloadIssueList();
            updateHeaderColor();
            mActivity.invalidateTitle();
        }
    }

    private void reloadIssueList() {
        mActivity.invalidateFragments();
    }

    private void toggleStateFilter() {
        mShowingClosed = !mShowingClosed;
        reloadIssueList();
        updateHeaderColor();
        mActivity.invalidateTitle();
        mActivity.supportInvalidateOptionsMenu();
    }

    private void updateHeaderColor() {
        if (mShowingClosed) {
            // Closed: red
            mHeaderColorAttrs = new int[] {
                R.attr.colorIssueClosed, R.attr.colorIssueClosedDark
            };
        } else {
            // Open: use app primary orange, not issue-open green
            mHeaderColorAttrs = null;
        }
        mActivity.invalidateTabs();
    }

    private String getSortOrderPrefKey() {
        return mIsPullRequest ? "home_pr_list_sort_order" : "home_issue_list_sort_order";
    }

    private String getSortDirPrefKey() {
        return mIsPullRequest ? "home_pr_list_sort_dir" : "home_issue_list_sort_dir";
    }
}
