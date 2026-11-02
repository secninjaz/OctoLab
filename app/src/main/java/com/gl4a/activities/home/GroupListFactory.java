package com.gl4a.activities.home;

import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.GroupListFragment;
import com.gl4a.widget.GroupTree;

/** Drawer entry "Groups": the user's groups (#172). */
public class GroupListFactory extends FragmentFactory {
    private static final int[] TAB_TITLES = new int[] {
        R.string.groups
    };

    private GroupListFragment mFragment;

    protected GroupListFactory(HomeActivity activity) {
        super(activity);
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        mFragment = (GroupListFragment) f;
        super.onFragmentInstantiated(f, position);
        mActivity.invalidateTitle();
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mFragment) mFragment = null;
        super.onFragmentDestroyed(f);
    }

    /** Search and the sort/filter panel's button, as Projects has them (#309). */
    @Override
    protected boolean onCreateOptionsMenu(Menu menu) {
        mActivity.getMenuInflater().inflate(R.menu.repo_list_menu, menu);
        MenuItem searchItem = menu.findItem(R.id.search);
        androidx.appcompat.widget.SearchView searchView =
                (androidx.appcompat.widget.SearchView) searchItem.getActionView();
        com.gl4a.utils.UiUtils.tintSearchViewIcons(searchView);
        searchView.setOnQueryTextListener(
                new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (mFragment != null) mFragment.search(query);
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (mFragment != null) mFragment.search(null);
                return true;
            }
        });
        return true;
    }

    @Override
    protected boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.filter) {
            mActivity.initiateFilter();
            return true;
        }
        return false;
    }

    // Sort and filter in the side panel, as Projects has them, the choices shown alike (#308)
    private static final java.util.List<Integer> SORT_IDS =
            java.util.Arrays.asList(R.id.group_sort_name, R.id.group_sort_activity);
    private static final java.util.List<Integer> FILTER_IDS =
            java.util.Arrays.asList(R.id.group_filter_mine, R.id.group_filter_owner);

    @Override
    protected int[] getToolDrawerMenuResIds() {
        return new int[] { R.menu.group_sort, R.menu.group_filter };
    }

    @Override
    protected void prepareToolDrawerMenu(Menu menu) {
        super.prepareToolDrawerMenu(menu);
        com.gl4a.utils.ChoiceMenus.mark(menu, SORT_IDS, GroupTree.isSortByActivity(mActivity)
                ? R.id.group_sort_activity : R.id.group_sort_name);
        com.gl4a.utils.ChoiceMenus.mark(menu, FILTER_IDS,
                GroupListFragment.SCOPE_OWNER.equals(GroupListFragment.scope(mActivity))
                        ? R.id.group_filter_owner : R.id.group_filter_mine);
    }

    @Override
    protected boolean onDrawerItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (FILTER_IDS.contains(id)) {
            GroupListFragment.setScope(mActivity, id == R.id.group_filter_owner
                    ? GroupListFragment.SCOPE_OWNER : GroupListFragment.SCOPE_MINE);
            if (mFragment != null) mFragment.onScopeChanged();
        } else if (SORT_IDS.contains(id)) {
            GroupTree.onSortItemSelected(item, mActivity);
            if (mFragment != null) mFragment.onSortChanged();
        } else {
            return super.onDrawerItemSelected(item);
        }
        mActivity.doInvalidateOptionsMenuAndToolDrawer();
        mActivity.invalidateTitle();
        return true;
    }

    /** The current filter, under the title (#308). */
    @Override
    protected CharSequence getSubtitle() {
        return mActivity.getString(
                GroupListFragment.SCOPE_OWNER.equals(GroupListFragment.scope(mActivity))
                        ? R.string.group_filter_owner : R.string.group_filter_mine);
    }

    @Override
    @StringRes
    protected int getTitleResId() {
        return R.string.groups;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TAB_TITLES;
    }

    @Override
    protected Fragment makeFragment(int position) {
        return GroupListFragment.newInstance();
    }
}
