package com.gl4a.activities.home;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.GroupListFragment;

/** Drawer entry "Groups": the user's groups (#172). */
public class GroupListFactory extends FragmentFactory {
    private static final int[] TAB_TITLES = new int[] {
        R.string.groups
    };

    protected GroupListFactory(HomeActivity activity) {
        super(activity);
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
