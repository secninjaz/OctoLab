package com.gl4a.activities.home;
import com.gl4a.gitlab.service.GitLabGroupService;
import com.gl4a.gitlab.model.GitLabUser;

import android.content.Context;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
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
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;

import java.util.List;

import io.reactivex.disposables.Disposable;

public class NewsFeedFactory extends FragmentFactory implements Spinner.OnItemSelectedListener {
    private final String mUserLogin;
    private GitLabUser mSelf;
    private GitLabUser mSelectedOrganization;
    private List<GitLabUser> mUserScopes;
    private Disposable mOrganizationSubscription;

    private static final int ID_LOADER_ORGS = 100;

    private static final int[] TAB_TITLES = new int[] {
        R.string.activity_filter_your_activity,
        R.string.activity_filter_your_projects,
        R.string.activity_filter_starred
    };

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
            case 1:
                // Events from projects you're a member of
                return ProjectScopeEventListFragment.newInstance(ProjectScopeEventListFragment.SCOPE_MEMBER);
            case 2:
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
        if (mUserScopes == null || mSelf == null) {
            return super.onCreateOptionsMenu(menu);
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
            nameView.setText(user.login());

            return convertView;
        }
    }
}