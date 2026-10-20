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
    public static final String EXTRA_NOTIFICATION_ACCOUNT = "notification_account";
    /** From a to-do's notification (#178): the to-do's target, opened after the account switch. */
    public static final String EXTRA_OPEN_URL = "open_url";
    /** From a to-do's notification (#178): marked done when opened, as in the To-dos list. */
    public static final String EXTRA_TODO_ID = "todo_id";

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
    private TextView mUserNameView;
    private ImageView mDrawerSwitcher;
    private String mUserLogin;
    private GitLabUser mUserInfo;
    private int mSelectedFactoryId;
    private boolean mDrawerInAccountMode;
    private Menu mLeftDrawerMenu;
    // To-dos' pending count at the drawer row's right end (#290)
    private TextView mNotificationsIndicator;
    private boolean mTodoIndicatorVisible;
    private MenuItem mNotificationsMenuItem;
    // To-dos | Participating (#280): GitLab's pending to-do count (-1 unknown), and the unread
    // participating notifications per account, for the bell, the switcher and the tabs
    private int mTodoCount = -1;
    /** A screen switch is moving the pager: not a tab the user chose (#284). */
    private boolean mSwitchingScreen;
    private java.util.Map<String, Integer> mParticipatingUnread = new java.util.HashMap<>();
    private TextView mParticipatingBadge;
    private View mOtherAccountsDot;
    private View mParticipatingBell;
    private final Runnable mOnParticipatingChanged = this::refreshParticipatingCounts;

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

    private final SparseArray<String> mOtherAccountLogins = new SparseArray<>();

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
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationAccountSwitch(intent);
    }

    private void handleNotificationAccountSwitch(android.content.Intent intent) {
        if (intent == null) return;
        String notifAccount = intent.getStringExtra(EXTRA_NOTIFICATION_ACCOUNT);
        if (notifAccount == null) return;
        intent.removeExtra(EXTRA_NOTIFICATION_ACCOUNT);
        String active = com.gl4a.Gl4Application.get().getAuthLogin();
        if (!notifAccount.equals(active)) {
            switchActiveUser(notifAccount);
        }
        // Navigate to the notifications tab regardless.
        mSelectedFactoryId = R.id.notifications;
        switchTo(mSelectedFactoryId, getFactoryForItem(mSelectedFactoryId));
        openTodoTarget(intent);
    }

    /**
     * Opens a to-do notification's target in-app, with the to-do's account now active, and
     * marks the to-do done (#178). Without a target URL the To-dos list stays open. Every way
     * in from a notification comes here, the app running or not (#275).
     */
    private void openTodoTarget(android.content.Intent intent) {
        String url = intent.getStringExtra(EXTRA_OPEN_URL);
        long todoId = intent.getLongExtra(EXTRA_TODO_ID, -1);
        intent.removeExtra(EXTRA_OPEN_URL);
        intent.removeExtra(EXTRA_TODO_ID);
        if (todoId > 0) {
            com.gl4a.ServiceFactory.get(com.gl4a.gitlab.service.GitLabTodoService.class, false)
                    .markAsDone(todoId)
                    .subscribeOn(io.reactivex.schedulers.Schedulers.io())
                    .subscribe(response -> { }, error -> android.util.Log.d(
                            Gl4Application.LOG_TAG, "Marking to-do done failed", error));
        } else if (todoId < -1) {
            // A participating comment's notification (#267): not shown again once opened (#275),
            // and read in To-dos | Participating (#280)
            com.gl4a.worker.NotificationsWorker.handleParticipatingOpened(this, todoId);
            String login = Gl4Application.get().getAuthLogin();
            if (login != null) {
                com.gl4a.utils.ParticipatingInbox.get(this).markRead(login, -todoId);
            }
        }
        if (url != null && !url.trim().isEmpty()) {
            com.gl4a.utils.IntentUtils.openLinkInternallyOrExternally(this,
                    android.net.Uri.parse(url));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // If launched from a notification for a different account, switch before setting up UI.
        String notifAccount = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_NOTIFICATION_ACCOUNT) : null;
        if (notifAccount != null) {
            getIntent().removeExtra(EXTRA_NOTIFICATION_ACCOUNT);
            String active = com.gl4a.Gl4Application.get().getAuthLogin();
            if (!notifAccount.equals(active)) {
                com.gl4a.Gl4Application.get().setActiveLogin(notifAccount);
            }
        }
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

        super.onCreate(savedInstanceState);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setHomeButtonEnabled(true);

        loadUserInfo(false);
        loadNotificationIndicator(false);
        mFactory.onStartLoadingData();

        // Opened from a to-do's notification: go to its target (#178)
        if (savedInstanceState == null && getIntent() != null) {
            openTodoTarget(getIntent());
        }

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

    public void setNotificationsIndicatorVisible(boolean visible) {
        mTodoIndicatorVisible = visible;
        if (mNotificationsIndicator != null) {
            mNotificationsMenuItem.setIcon(visible
                    ? R.drawable.icon_notifications_unread
                    : R.drawable.icon_notifications);
        }
        updateTodoBadge();
    }

    /** To-dos' pending count where its dot was (#290); a dot while the count isn't known. */
    private void updateTodoBadge() {
        if (mNotificationsIndicator == null) return;
        boolean show = mTodoIndicatorVisible && mTodoCount != 0;
        mNotificationsIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        mNotificationsIndicator.setText(mTodoCount > 0 ? formatCount(mTodoCount) : "");
        mNotificationsIndicator.setContentDescription(mTodoCount > 0
                ? getString(R.string.todo_pending_badge, mTodoCount) : null);
    }

    private static String formatCount(int count) {
        return count > 999 ? "999+" : String.valueOf(count);
    }

    /**
     * Gives each drawer item without its own view at the row's end an empty one: the drawer
     * reuses rows, and a row keeps the previous item's view (e.g. an account's count on
     * Groups) unless the new item brings one (#280, #290).
     */
    private void keepRowEndsApart(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.getActionView() == null) {
                item.setActionView(new android.widget.Space(this));
            }
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
            mNotificationsIndicator = actionView.findViewById(R.id.count_badge);
            updateTodoBadge();
        }
        keepRowEndsApart(menu);

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
        mUserNameView = header.findViewById(R.id.user_name);

        updateUserInfo();

        mDrawerSwitcher = header.findViewById(R.id.switcher);
        mDrawerSwitcher.setVisibility(View.VISIBLE);

        mDrawerSwitcher.setOnClickListener(this);

        // Participating notifications (#280): the bell and its count, the other accounts' count
        mParticipatingBell = header.findViewById(R.id.participating_bell);
        mParticipatingBadge = header.findViewById(R.id.participating_badge);
        mOtherAccountsDot = header.findViewById(R.id.switcher_dot);
        mParticipatingBell.setVisibility(View.VISIBLE);
        mParticipatingBell.setOnClickListener(v -> openParticipating());
        updateParticipatingBadges();

        View clickableBackground = header.findViewById(R.id.drawer_header);
        clickableBackground.setOnClickListener(this);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        super.onNavigationItemSelected(item);


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
            String login = mOtherAccountLogins.get(id);
            if (login != null) {
                switchActiveUser(login);
            }
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
        // Switching account stays on this screen and tab, which becomes the new account's last
        // tab for the screen (#284)
        getPrefs().edit().putInt(lastTabKey(login, mSelectedFactoryId),
                getPager().getCurrentItem()).apply();
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
            case R.id.my_groups:
                return new GroupListFactory(this);
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
    protected CharSequence getTabTitle(int position) {
        return mFactory != null ? mFactory.getTabTitle(position) : super.getTabTitle(position);
    }

    @Override
    protected int getTabBadgeCount(int position) {
        return mFactory != null ? mFactory.getTabBadgeCount(position) : 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.gl4a.utils.ParticipatingInbox.addListener(mOnParticipatingChanged);
        refreshParticipatingCounts();
    }

    @Override
    protected void onStop() {
        com.gl4a.utils.ParticipatingInbox.removeListener(mOnParticipatingChanged);
        super.onStop();
    }

    /** GitLab's count of pending to-dos; -1 if not known (yet). */
    int getTodoCount() {
        return mTodoCount;
    }

    /** The active account's unread participating notifications (#280). */
    int getParticipatingUnread() {
        Integer count = mUserLogin != null ? mParticipatingUnread.get(mUserLogin) : null;
        return count != null ? count : 0;
    }

    /** An account's count in the accounts list: its pending to-dos and unread (#290). */
    private int getAccountCount(String login) {
        Integer unread = mParticipatingUnread.get(login);
        int todos = com.gl4a.utils.TodoCounts.get(this, login);
        return (unread != null ? unread : 0) + Math.max(todos, 0);
    }

    /** Whether another account has pending to-dos or unread notifications (#290). */
    private boolean otherAccountsHaveCounts() {
        LongSparseArray<String> accounts = Gl4Application.get().getAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            String login = accounts.valueAt(i);
            if (!ApiHelpers.loginEquals(mUserLogin, login) && getAccountCount(login) > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void setTodoCount(int count) {
        // Kept for when this account is one of the others in the accounts list (#290)
        com.gl4a.utils.TodoCounts.set(this, mUserLogin, count);
        if (count == mTodoCount) return;
        mTodoCount = count;
        updateTodoBadge();
        if (mFactory instanceof NotificationListFactory) updateTabBadges();
    }

    @Override
    public void onTodoDone() {
        if (mTodoCount > 0) setTodoCount(mTodoCount - 1);
    }

    private void refreshParticipatingCounts() {
        android.content.Context app = getApplicationContext();
        io.reactivex.Single.fromCallable(
                        () -> com.gl4a.utils.ParticipatingInbox.get(app).unreadCounts())
                .compose(com.gl4a.utils.RxUtils::doInBackground)
                .subscribe(counts -> {
                    mParticipatingUnread = counts;
                    updateParticipatingBadges();
                    if (mFactory instanceof NotificationListFactory) updateTabBadges();
                }, error -> android.util.Log.d(Gl4Application.LOG_TAG,
                        "Participating counts unavailable", error));
    }

    /** The bell's count (this account) and the switcher's (the other accounts) (#280). */
    private void updateParticipatingBadges() {
        if (mParticipatingBadge != null) {
            showBadge(mParticipatingBadge, getParticipatingUnread(),
                    R.string.participating_unread_badge);
        }
        if (mOtherAccountsDot != null) {
            // A dot on the switcher; the counts are in the accounts list (#290)
            mOtherAccountsDot.setVisibility(otherAccountsHaveCounts()
                    ? View.VISIBLE : View.GONE);
        }
        if (mDrawerInAccountMode) updateAccountBadges();
    }

    private void showBadge(TextView badge, int count, int descriptionResId) {
        badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        badge.setText(count > 999 ? "999+" : String.valueOf(count));
        badge.setContentDescription(getString(descriptionResId, count));
    }

    /**
     * Each other account's count at its row's right end, as To-dos' (#290): its own view on
     * each account's item, so the drawer reusing a row can't leave it on another item (#280).
     */
    private void updateAccountBadges() {
        if (mLeftDrawerMenu == null) return;
        for (int i = 0; i < mOtherAccountLogins.size(); i++) {
            MenuItem item = mLeftDrawerMenu.findItem(mOtherAccountLogins.keyAt(i));
            if (item == null) continue;
            String login = mOtherAccountLogins.valueAt(i);
            int count = getAccountCount(login);
            CharSequence label = ApiHelpers.formatAccountLine(
                    Gl4Application.get().getNameForLogin(login), login);
            item.setTitle(label);
            if (!(item.getActionView() instanceof android.widget.FrameLayout)) {
                item.setActionView(R.layout.drawer_count_badge);
            }
            TextView badge = item.getActionView().findViewById(R.id.count_badge);
            badge.setBackgroundResource(R.drawable.count_badge_other);
            showBadge(badge, count, R.string.account_count_badge);
            androidx.core.view.MenuItemCompat.setContentDescription(item, count > 0
                    ? label + ", " + getString(R.string.account_count_badge, count)
                    : null);
        }
    }

    /** The bell: To-dos, on its Participating tab (#280). */
    private void openParticipating() {
        closeDrawers();
        if (mSelectedFactoryId != R.id.notifications) {
            MenuItem todos = mLeftDrawerMenu != null
                    ? mLeftDrawerMenu.findItem(R.id.notifications) : null;
            if (todos != null) todos.setChecked(true);
            switchTo(R.id.notifications, getFactoryForItem(R.id.notifications));
        }
        getPager().post(() -> getPager().setCurrentItem(NotificationListFactory.TAB_PARTICIPATING));
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

    /** Display name first, then "@username", like GitLab (#225). */
    private void updateHeaderNames(String name) {
        if (mUserNameView == null || mUserExtraView == null) return;
        boolean hasName = !TextUtils.isEmpty(name) && !name.matches("\\*+");
        mUserNameView.setText(hasName ? name : mUserLogin);
        mUserExtraView.setText(ApiHelpers.formatUsername(mUserLogin));
        mUserExtraView.setVisibility(hasName ? View.VISIBLE : View.GONE);
    }

    private void updateUserInfo() {
        if (mUserInfo == null) {
            // Until the profile loads, the name stored for the account
            updateHeaderNames(Gl4Application.get().getNameForLogin(mUserLogin));
            // No user profile yet (network failed or still loading).
            // Use stored avatar URL and userId so the disk cache can show the
            // last-known avatar instead of falling back to initials.
            com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
            long userId = app.getUserIdForLogin(mUserLogin);
            String storedUrl = app.getAvatarUrlForLogin(mUserLogin);
            if (mAvatarView != null && userId > 0 && storedUrl != null) {
                AvatarHandler.assignAvatar(mAvatarView, mUserLogin, userId, storedUrl);
            } else if (mAvatarView != null) {
                mAvatarView.setImageDrawable(
                        new AvatarHandler.DefaultAvatarDrawable(mUserLogin, null));
            }
            return;
        }
        if (mAvatarView != null) {
            AvatarHandler.assignAvatar(mAvatarView, mUserInfo);
        }
        updateHeaderNames(mUserInfo.name());
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
            mOtherAccountLogins.clear();

            int id = OTHER_ACCOUNTS_GROUP_BASE_ID;
            LongSparseArray<String> accounts = Gl4Application.get().getAccounts();
            for (int i = 0; i < accounts.size(); i++) {
                String login = accounts.valueAt(i);
                if (ApiHelpers.loginEquals(mUserLogin, login)) {
                    continue;
                }

                CharSequence label = ApiHelpers.formatAccountLine(
                        Gl4Application.get().getNameForLogin(login), login);
                MenuItem item = mLeftDrawerMenu.add(R.id.other_accounts, id, Menu.NONE, label);
                mOtherAccountLogins.put(id, login);
                id++;
                AvatarHandler.assignAvatar(this, item, login, accounts.keyAt(i));
            }
            updateAccountBadges();
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
        // The screen's own last tab for this account, not the previous screen's tab (#284)
        mSwitchingScreen = true;
        invalidateTabs();
        int count = getTabTitleResIds() != null ? getTabTitleResIds().length : 0;
        int tab = getPrefs().getInt(lastTabKey(mUserLogin, itemId), 0);
        getPager().setCurrentItem(count > 0 ? Math.min(Math.max(tab, 0), count - 1) : 0, false);
        mSwitchingScreen = false;
    }

    /** Where a screen's last tab is kept, per account (#284). */
    private String lastTabKey(String login, int itemId) {
        String screen;
        try {
            screen = getResources().getResourceEntryName(itemId);
        } catch (android.content.res.Resources.NotFoundException e) {
            screen = String.valueOf(itemId);
        }
        return "last_tab_" + login + "_" + screen;
    }

    @Override
    public void onPageSelected(int position) {
        super.onPageSelected(position);
        // Remembered as the screen's last tab for the account, also across restarts (#284);
        // not while a screen switch moves the pager
        if (!mSwitchingScreen && mUserLogin != null && mFactory != null) {
            getPrefs().edit().putInt(lastTabKey(mUserLogin, mSelectedFactoryId), position)
                    .apply();
        }
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
        // The account asked for: its count isn't kept for another after a switch (#282, #290)
        String login = mUserLogin;
        // GitLab's own count of them comes with the answer, for the To-dos tab (#280)
        service.listTodosByState("pending", 1, 1)
                .map(response -> com.gl4a.utils.TodoCounts.fromResponse(response,
                        ApiHelpers.throwOnFailure(response)))
                .compose(makeLoaderSingle(ID_LOADER_NOTIFICATIONS_INDICATOR, force))
                .subscribe(count -> {
                    if (!ApiHelpers.loginEquals(login, mUserLogin)) return;
                    setTodoCount(count);
                    setNotificationsIndicatorVisible(count != 0);
                }, this::handleLoadFailure);
    }
}
