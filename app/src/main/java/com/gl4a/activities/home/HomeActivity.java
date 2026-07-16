package com.gl4a.activities.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.appcompat.app.ActionBar;

import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.GitLabLoginActivity;
import com.gl4a.activities.UserActivity;
import com.gl4a.fragment.LoginModeChooserFragment;
import com.gl4a.fragment.NotificationListFragment;
import com.gl4a.fragment.RepositoryListContainerFragment;
import com.gl4a.fragment.SettingsFragment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabTodoService;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ActivityResultHelpers;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.UiUtils;

import java.util.HashMap;

public class HomeActivity extends BaseFragmentPagerActivity implements
        View.OnClickListener, RepositoryListContainerFragment.Callback,
        NotificationListFragment.ParentCallback, LoginModeChooserFragment.ParentCallback {
    public static Intent makeIntent(Context context, @IdRes int initialPageId) {
        String initialPage = START_PAGE_MAPPING.get(initialPageId);
        Intent intent = new Intent(context, HomeActivity.class);
        if (initialPage != null) {
            intent.putExtra("initial_page", initialPage);
        }
        return intent;
    }

    public static Intent makeNotificationsIntent(Context context, String repoOwner,
            String repoName) {
        return makeIntent(context, R.id.notifications)
                .putExtra(NotificationListFragment.EXTRA_INITIAL_REPO_OWNER, repoOwner)
                .putExtra(NotificationListFragment.EXTRA_INITIAL_REPO_NAME, repoName);
    }

    private FragmentFactory mFactory;
    private ImageView mAvatarView;
    private TextView mUserExtraView;
    private ImageView mDrawerSwitcher;
    private String mUserLogin;
    private GitLabUser mUserInfo;
    private int mSelectedFactoryId;
    private boolean mDrawerInAccountMode;
    private Menu mLeftDrawerMenu;
    private ImageView mNotificationsIndicator;
    private MenuItem mNotificationsMenuItem;
    private Drawable mNotificationsIndicatorIcon;

    private final ActivityResultLauncher<Void> mSettingsLauncher = registerForActivityResult(
            new ActivityResultHelpers.StartSettingsContract(),
            themeChanged -> {
                if (themeChanged) {
                    goToToplevelActivity();
                    finish();
                }
            });

    private static final String STATE_KEY_FACTORY_ITEM = "factoryItem";

    private static final int ID_LOADER_USER = 0;
    private static final int ID_LOADER_NOTIFICATIONS_INDICATOR = 1;

    private static final int OTHER_ACCOUNTS_GROUP_BASE_ID = 1000;

    private static final SparseArray<String> START_PAGE_MAPPING = new SparseArray<>();
    static {
        START_PAGE_MAPPING.put(R.id.news_feed, "newsfeed");
        START_PAGE_MAPPING.put(R.id.notifications, "notifications");
        START_PAGE_MAPPING.put(R.id.my_repos, "repos");
        START_PAGE_MAPPING.put(R.id.my_issues, "issues");
        START_PAGE_MAPPING.put(R.id.my_prs, "prs");
        START_PAGE_MAPPING.put(R.id.my_gists, "gists");
        START_PAGE_MAPPING.put(R.id.pub_timeline, "timeline");
        START_PAGE_MAPPING.put(R.id.bookmarks, "bookmarks");
        START_PAGE_MAPPING.put(R.id.search, "search");
        START_PAGE_MAPPING.put(R.id.trend, "trending");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mUserLogin = Gl4Application.get().getAuthLogin();
        if (TextUtils.isEmpty(mUserLogin)) {
            startActivity(new Intent(this, GitLabLoginActivity.class));
            finish();
            return;
        }
        if (savedInstanceState != null) {
            mSelectedFactoryId = savedInstanceState.getInt(STATE_KEY_FACTORY_ITEM);
        } else {
            mSelectedFactoryId = determineInitialPage();
        }
        mFactory = getFactoryForItem(mSelectedFactoryId);

        mNotificationsIndicatorIcon =
                DrawableCompat.wrap(ContextCompat.getDrawable(this, R.drawable.circle).mutate());

        super.onCreate(savedInstanceState);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setHomeButtonEnabled(true);

        loadUserInfo(false);
        loadNotificationIndicator(false);
        mFactory.onStartLoadingData();

        // Request POST_NOTIFICATIONS permission on first launch (Android 13+).
        // Notifications are enabled by default, so we need permission upfront.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
            }
        }
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(mFactory.getTitleResId());
    }

    private void updateNotificationIndicator(int checkedItemId) {
        if (mNotificationsIndicator == null) {
            return;
        }

        @AttrRes int colorResId = checkedItemId == R.id.notifications
                ? androidx.appcompat.R.attr.colorAccent : android.R.attr.textColorPrimary;
        @ColorInt int tint = UiUtils.resolveColor(this, colorResId);
        DrawableCompat.setTint(mNotificationsIndicatorIcon, tint);
        mNotificationsIndicator.setImageDrawable(mNotificationsIndicatorIcon);
    }

    public void setNotificationsIndicatorVisible(boolean visible) {
        if (mNotificationsIndicator != null) {
            mNotificationsIndicator.setVisibility(visible ? View.VISIBLE : View.GONE);
            mNotificationsMenuItem.setIcon(visible
                    ? R.drawable.icon_notifications_unread
                    : R.drawable.icon_notifications);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_KEY_FACTORY_ITEM, mSelectedFactoryId);
        mFactory.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            mFactory.onRestoreInstanceState(savedInstanceState);
        }
    }

    @Override
    public void onClick(View view) {
        updateDrawerMode(!mDrawerInAccountMode);
    }

    @Override
    protected int getLeftNavigationDrawerMenuResource() {
        return R.menu.home_nav_drawer;
    }

    @Override
    protected int getInitialLeftDrawerSelection(Menu menu) {
        mLeftDrawerMenu = menu;

        mNotificationsMenuItem = menu.findItem(R.id.notifications);
        if (mNotificationsMenuItem != null) {
            View actionView = mNotificationsMenuItem.getActionView();
            mNotificationsIndicator = actionView.findViewById(R.id.notifications_indicator);
            updateNotificationIndicator(mSelectedFactoryId);
        }

        return mSelectedFactoryId;
    }

    @Override
    protected int[] getRightNavigationDrawerMenuResources() {
        return mFactory.getToolDrawerMenuResIds();
    }

    @Override
    protected int getInitialRightDrawerSelection() {
        return mFactory.getInitialToolDrawerSelection();
    }

    @Override
    protected void onPrepareRightNavigationDrawerMenu(Menu menu) {
        super.onPrepareRightNavigationDrawerMenu(menu);
        mFactory.prepareToolDrawerMenu(menu);
    }

    @Override
    protected void configureLeftDrawerHeader(View header) {
        super.configureLeftDrawerHeader(header);

        mAvatarView = header.findViewById(R.id.avatar);
        mUserExtraView = header.findViewById(R.id.user_extra);

        TextView userNameView = header.findViewById(R.id.user_name);
        userNameView.setText(mUserLogin);

        updateUserInfo();

        mDrawerSwitcher = header.findViewById(R.id.switcher);
        mDrawerSwitcher.setVisibility(View.VISIBLE);

        mDrawerSwitcher.setOnClickListener(this);

        View clickableBackground = header.findViewById(R.id.drawer_header);
        clickableBackground.setOnClickListener(this);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        super.onNavigationItemSelected(item);

        updateNotificationIndicator(item.getItemId());

        if (mFactory != null && mFactory.onDrawerItemSelected(item)) {
            return true;
        }

        int id = item.getItemId();
        FragmentFactory factory = getFactoryForItem(id);

        if (factory != null) {
            switchTo(id, factory);
            return true;
        }

        switch (id) {
            case R.id.profile:
                startActivity(UserActivity.makeIntent(this, mUserLogin));
                return true;
            case R.id.logout:
                Gl4Application.get().logout();
                goToToplevelActivity();
                finish();
                return true;
            case R.id.add_account:
                LoginModeChooserFragment.newInstance().show(getSupportFragmentManager(), "loginmode");
                return true;
            case R.id.settings_item:
                mSettingsLauncher.launch(null);
                return true;
        }

        int accountCount = Gl4Application.get().getAccounts().size();
        if (id >= OTHER_ACCOUNTS_GROUP_BASE_ID && id < OTHER_ACCOUNTS_GROUP_BASE_ID + accountCount) {
            switchActiveUser(item.getTitle().toString());
            return true;
        }

        return false;
    }

    @Override
    protected void onDrawerClosed(boolean right) {
        super.onDrawerClosed(right);
        if (!right) {
            updateDrawerMode(false);
        }
    }

    private void switchActiveUser(String login) {
        Gl4Application.get().setActiveLogin(login);
        mUserLogin = login;
        onRefresh();
        closeDrawers();
        switchTo(mSelectedFactoryId, getFactoryForItem(mSelectedFactoryId));
        recreate();
    }

    private FragmentFactory getFactoryForItem(int id) {
        switch (id) {
            case R.id.news_feed:
                return new NewsFeedFactory(this, mUserLogin);
            case R.id.notifications:
                return new NotificationListFactory(this);
            case R.id.my_repos:
                return new RepositoryFactory(this, mUserLogin, getPrefs());
            case R.id.my_issues:
                return new IssueListFactory(this, mUserLogin, false, getPrefs());
            case R.id.my_prs:
                return new IssueListFactory(this, mUserLogin, true, getPrefs());
            case R.id.my_gists:
                return new GistFactory(this, mUserLogin);
            case R.id.search:
                return new SearchFactory(this);
            case R.id.bookmarks:
                return new BookmarkFactory(this, mUserLogin, getPrefs());
            case R.id.pub_timeline:
                return new TimelineFactory(this);
            case R.id.trend:
                return new TrendingFactory(this);
        }
        return null;
    }

    @Override
    protected int[] getTabTitleResIds() {
        return mFactory.getTabTitleResIds();
    }

    @Override
    protected int[] getHeaderColorAttrs() {
        return mFactory.getHeaderColorAttrs();
    }

    @Override
    protected Fragment makeFragment(int position) {
        return mFactory.makeFragment(position);
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        mFactory.onFragmentInstantiated(f, position);
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        mFactory.onFragmentDestroyed(f);
    }

    @Override
    public void onLoginStartOauth() {
        GitLabLoginActivity.launchOauthLogin(this);
    }

    @Override
    public void onLoginFinished(String token, GitLabUser user) {
        Gl4Application.get().addAccount(user, token);
        switchActiveUser(user.login());
    }

    @Override
    public void onLoginFailed(Throwable error) {
        // TODO
    }

    @Override
    public void onLoginCanceled() {
        // Nothing to do
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (mFactory.onCreateOptionsMenu(menu)) {
            return true;
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mFactory.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected Intent navigateUp() {
        return getToplevelActivityIntent();
    }

    @Override
    public void onRefresh() {
        loadUserInfo(true);
        loadNotificationIndicator(true);
        mFactory.onRefresh();
        super.onRefresh();
    }

    @Override
    public void supportInvalidateOptionsMenu() {
        //noinspection StatementWithEmptyBody
        if (mFactory instanceof RepositoryFactory) {
            // happens when load is done; we ignore it to avoid closing the IME
        } else {
            super.supportInvalidateOptionsMenu();
        }
    }

    @Override
    public void onBackPressed() {
        FragmentManager fm = getSupportFragmentManager();
        if (!closeDrawers() && fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        } else {
            int initialPage = determineInitialPage();
            if (mSelectedFactoryId != initialPage) {
                switchTo(initialPage, getFactoryForItem(initialPage));
                if (mLeftDrawerMenu != null) {
                    mLeftDrawerMenu.findItem(initialPage).setChecked(true);
                }
            } else {
                super.onBackPressed();
            }
        }
    }

    @Override
    public void initiateFilter() {
        toggleRightSideDrawer();
    }

    @Override
    protected boolean fragmentNeedsRefresh(Fragment object) {
        return true;
    }

    public void doInvalidateOptionsMenuAndToolDrawer() {
        super.supportInvalidateOptionsMenu();
        updateRightNavigationDrawer();
    }

    @Override
    public void invalidateTabs() {
        super.invalidateTabs();
    }

    @Override
    public void invalidateFragments() {
        super.invalidateFragments();
    }

    public void toggleToolDrawer() {
        toggleRightSideDrawer();
    }

    public void invalidateTitle() {
        getSupportActionBar().setTitle(mFactory.getTitleResId());
    }

    private int determineInitialPage() {
        final String initialPage;
        if (getIntent().hasExtra("initial_page")) {
            initialPage = getIntent().getStringExtra("initial_page");
            getIntent().removeExtra("initial_page");
        } else {
            final String prefPage = getPrefs().getString(SettingsFragment.KEY_START_PAGE, "notifications");
            initialPage = TextUtils.equals(prefPage, "last")
                    ? getPrefs().getString("last_selected_home_page", "notifications")
                    : prefPage;
        }
        for (int i = 0; i < START_PAGE_MAPPING.size(); i++) {
            if (TextUtils.equals(initialPage, START_PAGE_MAPPING.valueAt(i))) {
                return START_PAGE_MAPPING.keyAt(i);
            }
        }
        return R.id.notifications;
    }

    private void updateUserInfo() {
        if (mUserInfo == null) {
            mAvatarView.setImageDrawable(new AvatarHandler.DefaultAvatarDrawable(mUserLogin, null));
            return;
        }
        if (mAvatarView != null) {
            AvatarHandler.assignAvatar(mAvatarView, mUserInfo);
        }
        if (mUserExtraView != null) {
            if (TextUtils.isEmpty(mUserInfo.name())) {
                mUserExtraView.setVisibility(View.GONE);
            } else {
                mUserExtraView.setText(mUserInfo.name());
                mUserExtraView.setVisibility(View.VISIBLE);
            }
        }
        mFactory.setUserInfo(mUserInfo);
    }

    private void updateDrawerMode(boolean accountMode) {
        mLeftDrawerMenu.setGroupVisible(R.id.my_items, !accountMode);
        mLeftDrawerMenu.setGroupVisible(R.id.navigation, !accountMode);
        mLeftDrawerMenu.setGroupVisible(R.id.explore, !accountMode);
        mLeftDrawerMenu.setGroupVisible(R.id.settings, !accountMode);
        mLeftDrawerMenu.setGroupVisible(R.id.account, accountMode);
        mLeftDrawerMenu.setGroupVisible(R.id.other_accounts, accountMode);

        if (accountMode) {
            for (int i = 0; ; i++) {
                MenuItem item = mLeftDrawerMenu.findItem(OTHER_ACCOUNTS_GROUP_BASE_ID + i);
                if (item == null) {
                    break;
                }
                mLeftDrawerMenu.removeItem(item.getItemId());
            }

            int id = OTHER_ACCOUNTS_GROUP_BASE_ID;
            LongSparseArray<String> accounts = Gl4Application.get().getAccounts();
            for (int i = 0; i < accounts.size(); i++) {
                String login = accounts.valueAt(i);
                if (ApiHelpers.loginEquals(mUserLogin, login)) {
                    continue;
                }

                MenuItem item = mLeftDrawerMenu.add(R.id.other_accounts, id++, Menu.NONE, login);
                AvatarHandler.assignAvatar(this, item, login, accounts.keyAt(i));
            }
        }

        mDrawerSwitcher.setImageResource(accountMode
                ? R.drawable.drop_up_arrow_white : R.drawable.drop_down_arrow_white);
        mDrawerInAccountMode = accountMode;
    }

    private void switchTo(int itemId, FragmentFactory factory) {
        if (mFactory != null) {
            mFactory.onDestroy();
        }
        mFactory = factory;
        mSelectedFactoryId = itemId;
        mFactory.setUserInfo(mUserInfo);
        mFactory.onStartLoadingData();

        getPrefs().edit()
                .putString("last_selected_home_page", START_PAGE_MAPPING.get(mSelectedFactoryId))
                .apply();

        setErrorViewVisibility(false, null);
        updateRightNavigationDrawer();
        super.supportInvalidateOptionsMenu();
        getSupportFragmentManager().popBackStackImmediate(null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE);
        invalidateTitle();
        invalidateTabs();
    }

    private void loadUserInfo(boolean force) {
        GitLabUserService service = ServiceFactory.get(GitLabUserService.class, force);
        service.getCurrentUser()
                .map(ApiHelpers::throwOnFailure)
                .compose(makeLoaderSingle(ID_LOADER_USER, force))
                .subscribe(result -> {
                    Gl4Application.get().setCurrentAccountInfo(result);
                    mUserInfo = result;
                    updateUserInfo();
                }, this::handleLoadFailure);
    }

    private void loadNotificationIndicator(boolean force) {
        // Use GitLabTodoService to check for pending todos (equivalent to unread notifications)
        GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, force, 1);
        service.listTodosByState("pending", 1, 1)
                .map(ApiHelpers::throwOnFailure)
                .map(result -> !result.isEmpty())
                .compose(makeLoaderSingle(ID_LOADER_NOTIFICATIONS_INDICATOR, force))
                .subscribe(this::setNotificationsIndicatorVisible, this::handleLoadFailure);
    }
}
