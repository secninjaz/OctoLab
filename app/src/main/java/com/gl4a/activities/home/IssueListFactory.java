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

    // Issues: Assigned / Created / Mentioned / Participating (Assigned first, #323)
    private static final int[] ISSUE_TAB_TITLES = new int[] {
            R.string.assigned, R.string.created, R.string.mentioned, R.string.participating
    };
    // MRs: Assigned / Created / Reviews / Mentioned  (Reviews = scope=reviews_for_me)
    private static final int[] MR_TAB_TITLES = new int[] {
            R.string.assigned, R.string.created, R.string.reviews, R.string.mentioned,
            // The shared definition of participating, as for issues (#267)
            R.string.participating
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
        // Initialize directly — updateHeaderColor() calls invalidateTabs() which NPEs
        // because mAdapter is null in the constructor at this point.
        mHeaderColorAttrs = new int[] { R.attr.colorIssueOpen, R.attr.colorIssueOpenDark };

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

    // Issue scopes: Assigned / Created / Mentioned / Participating
    private static final String[] ISSUE_SCOPES =
            { "assigned_to_me", "created_by_me", "mentioned", "participating" };
    // MR scopes:   Assigned / Created / Reviews / Mentioned / Participating
    private static final String[] MR_SCOPES =
            { "assigned_to_me", "created_by_me", "reviews_for_me", "mentioned", "participating" };

    private static final String PREF_ASSIGNED_FIRST = "issue_tabs_assigned_first";

    /**
     * Once, after Assigned and Created swapped places (#323): the last tab each account used
     * on these screens is kept by position, so a remembered Created (0) becomes 1 and Assigned
     * (1) becomes 0, and everyone stays on the tab they were on.
     */
    public static void moveRememberedTabs(SharedPreferences prefs) {
        if (prefs.getBoolean(PREF_ASSIGNED_FIRST, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("last_tab_")
                    || !(key.endsWith("_my_issues") || key.endsWith("_my_prs"))
                    || !(e.getValue() instanceof Integer)) {
                continue;
            }
            int tab = (Integer) e.getValue();
            if (tab == 0 || tab == 1) editor.putInt(key, 1 - tab);
        }
        editor.putBoolean(PREF_ASSIGNED_FIRST, true).apply();
    }

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
            mHeaderColorAttrs = mIsPullRequest
                ? new int[] { R.attr.colorMergeRequestClosed, R.attr.colorMergeRequestClosedDark }
                : new int[] { R.attr.colorIssueClosed, R.attr.colorIssueClosedDark };
        } else {
            // Open: green tab indicator (toolbar background stays light grey via BasePagerActivity)
            mHeaderColorAttrs = new int[] {
                R.attr.colorIssueOpen, R.attr.colorIssueOpenDark
            };
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
