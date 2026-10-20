package com.gl4a.activities.home;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.gl4a.R;
import com.gl4a.fragment.NotificationListFragment;
import com.gl4a.fragment.ParticipatingListFragment;

/** To-dos | Participating (#280): GitLab's to-dos, and the participating notifications kept. */
public class NotificationListFactory extends FragmentFactory {
    public static final int TAB_PARTICIPATING = 1;

    private static final int[] TAB_TITLES =  new int[] {
        R.string.notifications, R.string.participating
    };

    protected NotificationListFactory(HomeActivity activity) {
        super(activity);
    }

    @Override
    protected @StringRes int getTitleResId() {
        return R.string.notifications;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TAB_TITLES;
    }

    /** The pending to-dos (GitLab's count) or the unread participating ones. */
    @Override
    protected int getTabBadgeCount(int position) {
        return position == TAB_PARTICIPATING
                ? mActivity.getParticipatingUnread() : mActivity.getTodoCount();
    }

    @Override
    protected Fragment makeFragment(int position) {
        if (position == TAB_PARTICIPATING) {
            return ParticipatingListFragment.newInstance();
        }
        return NotificationListFragment.newInstance();
    }
}
