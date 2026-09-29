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
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mFragment) mFragment = null;
        super.onFragmentDestroyed(f);
    }

    @Override
    protected boolean onCreateOptionsMenu(Menu menu) {
        mActivity.getMenuInflater().inflate(R.menu.group_sort, menu);
        GroupTree.prepareSortMenu(menu, mActivity);
        return false;
    }

    @Override
    protected boolean onOptionsItemSelected(MenuItem item) {
        if (GroupTree.onSortItemSelected(item, mActivity)) {
            if (mFragment != null) mFragment.onSortChanged();
            return true;
        }
        return item.getItemId() == R.id.group_sort_name
                || item.getItemId() == R.id.group_sort_activity;
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
