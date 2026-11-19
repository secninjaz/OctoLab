package com.gl4a.activities.home;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.PrivateEventListFragment;
import com.gl4a.fragment.ProjectScopeEventListFragment;
import com.gl4a.fragment.RepositoryListFragment;
import com.gl4a.utils.ActivityFeedOptions;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.FilterIndicator;

import java.util.List;

import io.reactivex.disposables.Disposable;

public class NewsFeedFactory extends FragmentFactory implements Spinner.OnItemSelectedListener {
    private final String mUserLogin;
    private GitLabUser mSelf;
    private GitLabUser mSelectedOrganization;
    private List<GitLabUser> mUserScopes;
    private Disposable mOrganizationSubscription;

    private static final int ID_LOADER_ORGS = 100;

    // Your projects and Starred first, as GitLab's dashboard activity; your own last (#335)
    private static final int[] TAB_TITLES = new int[] {
        R.string.activity_filter_your_projects,
        R.string.activity_filter_starred,
        R.string.activity_filter_your_activity
    };
    private static final String PREF_PROJECTS_FIRST = "activity_tabs_projects_first";

    /**
     * Once, after the tabs moved (#335): the last tab each account used here is kept by
     * position, so a remembered Your activity (0) becomes 2, Your projects (1) 0 and Starred
     * (2) 1, and everyone stays on the tab they were on.
     */
    public static void moveRememberedTabs(android.content.SharedPreferences prefs) {
        if (prefs.getBoolean(PREF_PROJECTS_FIRST, false)) return;
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("last_tab_") || !key.endsWith("_news_feed")
                    || !(e.getValue() instanceof Integer)) {
                continue;
            }
            int tab = (Integer) e.getValue();
            if (tab >= 0 && tab <= 2) editor.putInt(key, (tab + 2) % 3);
        }
        editor.putBoolean(PREF_PROJECTS_FIRST, true).apply();
    }

    public NewsFeedFactory(HomeActivity activity, String userLogin) {
        super(activity);
        mUserLogin = userLogin;
    }

    @Override
    public @StringRes int getTitleResId() {
        return R.string.user_news_feed;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TAB_TITLES;
    }

    @Override
    protected Fragment makeFragment(int position) {
        switch (position) {
            case 0:
                // Events from projects you're a member of
                return ProjectScopeEventListFragment.newInstance(ProjectScopeEventListFragment.SCOPE_MEMBER);
            case 1:
                // Events from your starred projects
                return ProjectScopeEventListFragment.newInstance(ProjectScopeEventListFragment.SCOPE_STARRED);
            default:
                // Your own activity
                return PrivateEventListFragment.newInstance(mUserLogin,
                        mSelectedOrganization != null ? mSelectedOrganization.login() : null);
        }
    }

    @Override
    protected void setUserInfo(GitLabUser user) {
        mSelf = user;
        mActivity.supportInvalidateOptionsMenu();
    }

    @Override
    protected void onStartLoadingData() {
        loadOrganizations(false);
    }

    @Override
    protected boolean onCreateOptionsMenu(Menu menu) {
        // Filter and sort as To-dos' and Starred's, the current ones checked and the buttons
        // marked when they aren't All and Newest first (#336)
        mActivity.getMenuInflater().inflate(R.menu.activity_feed_menu, menu);
        String filter = ActivityFeedOptions.filter();
        // Only the current one: in a single-choice group Android checks any item set at all,
        // even with false, so the last one (Pushes) always showed as chosen
        for (int i = 0; i < FILTERS.length; i++) {
            if (FILTERS[i].equals(filter)) menu.findItem(FILTER_ITEMS[i]).setChecked(true);
        }
        boolean oldest = ActivityFeedOptions.isOldestFirst();
        menu.findItem(oldest ? R.id.activity_sort_oldest : R.id.activity_sort_newest)
                .setChecked(true);
        FilterIndicator.apply(mActivity, menu.findItem(R.id.activity_filter), R.drawable.filter,
                ActivityFeedOptions.isFiltered());
        FilterIndicator.apply(mActivity, menu.findItem(R.id.activity_sort),
                R.drawable.menu_sort_order, oldest);
        if (mUserScopes == null || mSelf == null) {
            return true;
        }

        mActivity.getMenuInflater().inflate(R.menu.user_selector, menu);

        int selectedPosition = mSelectedOrganization != null
                ? mUserScopes.indexOf(mSelectedOrganization) : -1;
        Spinner spinner = (Spinner) menu.findItem(R.id.selector).getActionView();
        UserAdapter adapter = new UserAdapter(mActivity, mSelf, mUserScopes);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedPosition + 1);
        spinner.setGravity(Gravity.RIGHT);
        spinner.setOnItemSelectedListener(this);

        return true;
    }

    private static final int[] FILTER_ITEMS = {
        R.id.activity_filter_all, R.id.activity_filter_issues,
        R.id.activity_filter_merge_requests, R.id.activity_filter_comments,
        R.id.activity_filter_pushes,
    };
    private static final String[] FILTERS = {
        ActivityFeedOptions.FILTER_ALL, ActivityFeedOptions.FILTER_ISSUES,
        ActivityFeedOptions.FILTER_MERGE_REQUESTS, ActivityFeedOptions.FILTER_COMMENTS,
        ActivityFeedOptions.FILTER_PUSHES,
    };

    @Override
    protected boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        for (int i = 0; i < FILTERS.length; i++) {
            if (id == FILTER_ITEMS[i]) {
                ActivityFeedOptions.setFilter(FILTERS[i]);
                onOptionsChanged();
                return true;
            }
        }
        if (id == R.id.activity_sort_newest || id == R.id.activity_sort_oldest) {
            ActivityFeedOptions.setSort(id == R.id.activity_sort_oldest
                    ? ActivityFeedOptions.SORT_OLDEST : ActivityFeedOptions.SORT_NEWEST);
            onOptionsChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** What the tabs show, the title's second line and the buttons follow the new choice. */
    private void onOptionsChanged() {
        mActivity.invalidateFragments();
        mActivity.invalidateTitle();
        mActivity.supportInvalidateOptionsMenu();
    }

    /** The current filter and sort under the title, when they aren't the default (#336). */
    @Override
    protected CharSequence getSubtitle() {
        return ActivityFeedOptions.summary(mActivity);
    }

    @Override
    protected void onRefresh() {
        mSelf = null;
        mUserScopes = null;
        loadOrganizations(true);
        mActivity.supportInvalidateOptionsMenu();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOrganizationSubscription != null) {
            mOrganizationSubscription.dispose();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        GitLabUser selectedOrganization = position != 0 ? mUserScopes.get(position - 1) : null;
        boolean isSameUser = selectedOrganization == null || mSelectedOrganization == null
                ? selectedOrganization == mSelectedOrganization
                : selectedOrganization.equals(mSelectedOrganization);
        if (!isSameUser) {
            mSelectedOrganization = selectedOrganization;
            mActivity.invalidateFragments();
        }

    }

    @Override
    public void onNothingSelected(AdapterView<?> view) {
        if (mSelectedOrganization != null) {
            mSelectedOrganization = null;
            mActivity.invalidateFragments();
        }
    }

    private void loadOrganizations(boolean force) {
        // GitLab uses groups instead of GitHub organizations; stub with empty list
        mOrganizationSubscription = io.reactivex.Single.<java.util.List<GitLabUser>>just(new java.util.ArrayList<>())
                .compose(mActivity.makeLoaderSingle(ID_LOADER_ORGS, force))
                .subscribe(result -> {
                    mUserScopes = result.isEmpty() ? null : result;
                    mActivity.supportInvalidateOptionsMenu();
                }, mActivity::handleLoadFailure);
    }

    private static class UserAdapter extends BaseAdapter {
        private final GitLabUser mSelf;
        private final List<GitLabUser> mUsers;
        private final LayoutInflater mInflater;

        public UserAdapter(Context context, GitLabUser self, List<GitLabUser> users) {
            super();
            mInflater = LayoutInflater.from(context);
            mSelf = self;
            mUsers = users;
        }

        @Override
        public int getCount() {
            return mUsers.size() + 1;
        }

        @Override
        public GitLabUser getItem(int position) {
            return position == 0 ? mSelf : mUsers.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.user_type_small, parent, false);
            }

            GitLabUser user = getItem(position);
            ImageView avatar = (ImageView) convertView;
            AvatarHandler.assignAvatar(avatar, user);

            return convertView;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.user_type_popup, parent, false);
            }

            GitLabUser user = getItem(position);

            ImageView avatar = convertView.findViewById(R.id.iv_gravatar);
            AvatarHandler.assignAvatar(avatar, user);

            TextView nameView = convertView.findViewById(R.id.tv_title);
            nameView.setText(com.gl4a.utils.ApiHelpers.getUserDisplayName(convertView.getContext(), user));

            return convertView;
        }
    }
}