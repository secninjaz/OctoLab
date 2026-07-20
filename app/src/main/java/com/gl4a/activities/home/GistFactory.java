package com.gl4a.activities.home;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.GistListFragment;

public class GistFactory extends FragmentFactory {
    private final String mUserLogin;

    // GitLab has no "starred snippets" concept; single tab shows the user's own snippets.
    private static final int[] TAB_TITLES = new int[] {
        R.string.my_gists
    };

    public GistFactory(HomeActivity activity, String userLogin) {
        super(activity);
        mUserLogin = userLogin;
    }

    @Override
    protected @StringRes int getTitleResId() {
        return R.string.my_gists;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TAB_TITLES;
    }

    @Override
    protected Fragment makeFragment(int position) {
        return GistListFragment.newInstance(mUserLogin, -1L);
    }
}
