package com.gl4a.activities;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.gl4a.BaseFragmentPagerActivity;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.db.BookmarksProvider;
import com.gl4a.fragment.PublicEventListFragment;
import com.gl4a.fragment.UserFragment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.StringUtils;

public class UserActivity extends BaseFragmentPagerActivity {
    public static Intent makeIntent(Context context, GitLabUser user) {
        if (user == null) return null;
        Intent intent = new Intent(context, UserActivity.class)
                .putExtra("login", user.login());
        // Pass the user ID when available so we can skip the username search,
        // which fails on some GitLab instances due to permission restrictions.
        if (user.id() > 0) {
            intent.putExtra("user_id", user.id());
        }
        return intent;
    }

    public static Intent makeIntent(Context context, String login) {
        if (login == null) {
            return null;
        }
        return new Intent(context, UserActivity.class)
                .putExtra("login", login);
    }

    private String mUserLogin;
    private long mUserId = -1L;
    private GitLabUser mUser;
    private UserFragment mUserFragment;

    private static final int ID_LOADER_USER = 0;

    private static final int[] TAB_TITLES = new int[] {
        R.string.about, R.string.user_public_activity
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentShown(false);
        loadUser(false);
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        if (mUserLogin != null && mUserLogin.endsWith("[bot]")) {
            return mUserLogin.substring(0, mUserLogin.length() - 5);
        }
        return mUserLogin;
    }

    @Override
    protected void onInitExtras(Bundle extras) {
        super.onInitExtras(extras);
        mUserLogin = extras.getString("login");
        mUserId = extras.getLong("user_id", -1L);
    }

    @Override
    protected int[] getTabTitleResIds() {
        return TAB_TITLES;
    }

    @Override
    public void onRefresh() {
        mUser = null;
        setContentShown(false);
        invalidateTabs();
        invalidateOptionsMenu();
        loadUser(true);
        super.onRefresh();
    }

    @Override
    protected Fragment makeFragment(int position) {
        switch (position) {
            case 0: return UserFragment.newInstance(mUser);
            case 1: return PublicEventListFragment.newInstance(mUser);
        }
        return null;
    }

    @Override
    protected void onFragmentInstantiated(Fragment f, int position) {
        if (position == 0) {
            mUserFragment = (UserFragment) f;
        }
    }

    @Override
    protected void onFragmentDestroyed(Fragment f) {
        if (f == mUserFragment) {
            mUserFragment = null;
        }
    }

    @Override
    public boolean displayDetachAction() {
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.user_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem bookmarkAction = menu.findItem(R.id.bookmark);
        if (bookmarkAction != null) {
            String url = Gl4Application.get().getInstanceUrl() + "/" + mUserLogin;
            bookmarkAction.setTitle(BookmarksProvider.hasBookmarked(this, url)
                    ? R.string.remove_bookmark
                    : R.string.bookmark);
            bookmarkAction.setVisible(mUser != null);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected Intent navigateUp() {
        return getToplevelActivityIntent();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Uri url = IntentUtils.createBaseUriForUser(mUserLogin).build();
        switch (item.getItemId()) {
            case R.id.share: {
                String userName = mUser != null ? mUser.name() : null;
                int subjectId = StringUtils.isBlank(userName)
                        ? R.string.share_user_subject_loginonly : R.string.share_user_subject;
                IntentUtils.share(this, getString(subjectId, mUserLogin, userName), url);
                return true;
            }
            case R.id.browser:
                IntentUtils.launchBrowser(this, url);
                return true;
            case R.id.bookmark: {
                String urlString = url.toString();
                if (BookmarksProvider.hasBookmarked(this, urlString)) {
                    BookmarksProvider.removeBookmark(this, urlString);
                } else {
                    BookmarksProvider.saveBookmark(this, mUserLogin,
                            BookmarksProvider.Columns.TYPE_USER,
                            urlString, mUser.name(), true);
                }
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadUser(boolean force) {
        GitLabUserService service = ServiceFactory.get(GitLabUserService.class, force);
        io.reactivex.Single<GitLabUser> userSingle;

        if (mUserId > 0) {
            // Try by ID first (fastest). On restricted self-hosted instances GET /users/{id}
            // may return 404 for non-admin users — fall back to username search in that case.
            userSingle = service.getUser(mUserId)
                    .map(ApiHelpers::throwOnFailure)
                    .onErrorResumeNext(err -> {
                        if (mUserLogin == null) return io.reactivex.Single.error(err);
                        return service.searchUsers(mUserLogin, 1, 1)
                                .map(ApiHelpers::throwOnFailure)
                                .flatMap(users -> users.isEmpty()
                                        ? io.reactivex.Single.error(err)
                                        : service.getUser(users.get(0).id)
                                                .map(ApiHelpers::throwOnFailure));
                    });
        } else {
            userSingle = service.searchUsers(mUserLogin, 1, 1)
                    .map(ApiHelpers::throwOnFailure)
                    .flatMap(users -> {
                        if (users.isEmpty()) {
                            return io.reactivex.Single.error(
                                    new RuntimeException("User not found: " + mUserLogin));
                        }
                        return service.getUser(users.get(0).id)
                                .map(ApiHelpers::throwOnFailure);
                    });
        }

        userSingle
                .compose(makeLoaderSingle(ID_LOADER_USER, force))
                .subscribe(result -> {
                    mUser = result;
                    mUserId = result.id();
                    invalidateTabs();
                    setContentShown(true);
                    invalidateOptionsMenu();
                }, this::handleLoadFailure);
    }
}