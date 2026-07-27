package com.gl4a;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.Build;
import android.util.LongSparseArray;

import com.gl4a.fragment.SettingsFragment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.utils.StringUtils;
import com.gl4a.worker.NotificationsWorker;
import com.tspoon.traceur.Traceur;

import org.ocpsoft.prettytime.PrettyTime;

import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

public class Gl4Application extends Application implements
        androidx.work.Configuration.Provider,
        OnSharedPreferenceChangeListener {

    public static final String LOG_TAG = "Gl4a";
    public static final String DEFAULT_INSTANCE = "https://gitlab.com";

    private static Gl4Application sInstance;
    private PrettyTime mPt;

    private static final int THEME_DARK = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_SYSTEM = 2;

    private static final String KEY_VERSION = "version";
    private static final String KEY_ACTIVE_LOGIN = "active_login";
    private static final String KEY_ALL_LOGINS = "logins";
    private static final String KEY_PREFIX_TOKEN = "token_";
    private static final String KEY_PREFIX_USER_ID = "user_id_";
    private static final String KEY_PREFIX_TOKEN_TYPE = "token_type_";

    public static final String TOKEN_TYPE_PAT = "pat";
    public static final String TOKEN_TYPE_OAUTH = "oauth";
    private static final String KEY_INSTANCE_URL = "instance_url";
    private static final String KEY_PREFIX_INSTANCE_URL = "instance_url_";

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        SharedPreferences prefs = getPrefs();
        int prefsVersion = prefs.getInt(KEY_VERSION, 0);
        if (prefsVersion < 1) {
            prefs.edit().putInt(KEY_VERSION, 1).apply();
        }

        prefs.registerOnSharedPreferenceChangeListener(this);
        updateTheme(prefs);
        if (BuildConfig.DEBUG) Traceur.enableLogging();

        mPt = new PrettyTime();
        com.gl4a.utils.DebugLogger.get().init(this);
        ServiceFactory.initClient(this);
        updateNotificationWorker(prefs);
    }

    private void updateNotificationWorker(SharedPreferences prefs) {
        if (isAuthorized() && prefs.getBoolean(SettingsFragment.KEY_NOTIFICATIONS, false)) {
            int intervalMinutes = prefs.getInt(SettingsFragment.KEY_NOTIFICATION_INTERVAL, 15);
            NotificationsWorker.schedule(this, intervalMinutes);
        } else {
            NotificationsWorker.cancel(this);
        }
    }

    private void updateTheme(SharedPreferences prefs) {
        int theme = prefs.getInt(SettingsFragment.KEY_THEME,
                getResources().getInteger(R.integer.default_theme));
        int nightMode;
        switch (theme) {
            case THEME_DARK: nightMode = AppCompatDelegate.MODE_NIGHT_YES; break;
            case THEME_SYSTEM: nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
            default: nightMode = AppCompatDelegate.MODE_NIGHT_NO; break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mPt = new PrettyTime(newConfig.getLocales().get(0));
        } else {
            mPt = new PrettyTime(newConfig.locale);
        }
    }

    public PrettyTime getPrettyTimeInstance() { return mPt; }

    // --- Instance URL management ---

    public String getInstanceUrl() {
        // Global key always holds the current active account's URL.
        // setActiveLogin() restores it from the per-account slot on every switch.
        String url = getPrefs().getString(KEY_INSTANCE_URL, DEFAULT_INSTANCE);
        if (url == null || url.isEmpty()) url = DEFAULT_INSTANCE;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public void setInstanceUrl(String url) {
        if (url == null || url.trim().isEmpty()) url = DEFAULT_INSTANCE;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // Only write to the global key — this is called during the login flow for a
        // new account. Writing to the current account's per-account slot here would
        // overwrite its correct URL with the new account's URL, causing 401 on the
        // next switch back. addAccount() snapshots the global key to the per-account
        // slot once the new login is confirmed.
        getPrefs().edit().putString(KEY_INSTANCE_URL, url).apply();
        ServiceFactory.invalidateCache();
    }

    public String getInstanceBaseUrl() {
        return getInstanceUrl() + "/";
    }

    public String getApiBaseUrl() {
        return getInstanceUrl() + "/api/v4/";
    }

    // --- Auth ---

    public void setActiveLogin(String login) {
        Set<String> all = getPrefs().getStringSet(KEY_ALL_LOGINS, new HashSet<>());
        if (all.contains(login)) {
            // Restore this account's instance URL to the global key so that
            // getInstanceUrl() and the service factory immediately use the right base URL.
            String accountUrl = getPrefs().getString(KEY_PREFIX_INSTANCE_URL + login,
                    getPrefs().getString(KEY_INSTANCE_URL, DEFAULT_INSTANCE));
            getPrefs().edit()
                    .putString(KEY_ACTIVE_LOGIN, login)
                    .putString(KEY_INSTANCE_URL, accountUrl)
                    .apply();
            ServiceFactory.invalidateCache();
        }
    }

    public String getAuthLogin() {
        return getPrefs().getString(KEY_ACTIVE_LOGIN, null);
    }

    public LongSparseArray<String> getAccounts() {
        LongSparseArray<String> accounts = new LongSparseArray<>();
        for (String login : getPrefs().getStringSet(KEY_ALL_LOGINS, new HashSet<>())) {
            long id = getPrefs().getLong(KEY_PREFIX_USER_ID + login, -1);
            if (id > 0) accounts.put(id, login);
        }
        return accounts;
    }

    public String getAuthToken() {
        String login = getAuthLogin();
        return login != null ? getPrefs().getString(KEY_PREFIX_TOKEN + login, null) : null;
    }

    public void addAccount(GitLabUser user, String token, String tokenType) {
        SharedPreferences prefs = getPrefs();
        String login = user.login();
        Set<String> logins = StringUtils.getEditableStringSetFromPrefs(prefs, KEY_ALL_LOGINS);
        logins.add(login);
        // Snapshot the current instance URL into this account's per-account slot
        String currentUrl = getPrefs().getString(KEY_INSTANCE_URL, DEFAULT_INSTANCE);
        prefs.edit()
                .putString(KEY_ACTIVE_LOGIN, login)
                .putStringSet(KEY_ALL_LOGINS, logins)
                .putString(KEY_PREFIX_TOKEN + login, token)
                .putString(KEY_PREFIX_TOKEN_TYPE + login, tokenType)
                .putLong(KEY_PREFIX_USER_ID + login, user.id())
                .putString(KEY_PREFIX_INSTANCE_URL + login, currentUrl)
                .apply();
        ServiceFactory.invalidateCache();
        updateNotificationWorker(prefs);
    }

    /** Backwards-compat overload — defaults to PAT (most common). */
    public void addAccount(GitLabUser user, String token) {
        addAccount(user, token, TOKEN_TYPE_PAT);
    }

    public String getAuthTokenType() {
        String login = getAuthLogin();
        if (login == null) return TOKEN_TYPE_PAT;
        String type = getPrefs().getString(KEY_PREFIX_TOKEN_TYPE + login, null);
        // If no type stored, sniff: OAuth2 tokens are longer (40+ chars), PATs have glpat- prefix
        if (type == null) {
            String token = getPrefs().getString(KEY_PREFIX_TOKEN + login, "");
            type = (token != null && (token.startsWith("glpat-") || token.startsWith("glgat-")
                    || token.startsWith("gldt-") || token.startsWith("glsoat-")))
                    ? TOKEN_TYPE_PAT : TOKEN_TYPE_OAUTH;
        }
        return type;
    }

    public GitLabUser getCurrentAccountInfoForAvatar() {
        String login = getAuthLogin();
        if (login != null) {
            long userId = getPrefs().getLong(KEY_PREFIX_USER_ID + login, -1);
            if (userId >= 0) {
                return GitLabUser.create(login, userId);
            }
        }
        return null;
    }

    public void setCurrentAccountInfo(GitLabUser user) {
        getPrefs().edit()
                .putLong(KEY_PREFIX_USER_ID + user.login(), user.id())
                .apply();
    }

    public void logout() {
        String login = getAuthLogin();
        if (login == null) return;
        Set<String> logins = StringUtils.getEditableStringSetFromPrefs(getPrefs(), KEY_ALL_LOGINS);
        logins.remove(login);
        getPrefs().edit()
                .putString(KEY_ACTIVE_LOGIN, logins.size() > 0 ? logins.iterator().next() : null)
                .putStringSet(KEY_ALL_LOGINS, logins)
                .remove(KEY_PREFIX_TOKEN + login)
                .remove(KEY_PREFIX_USER_ID + login)
                // Fix: also remove token_type so that a re-login with a different type does not
                // inherit the stale value and send the wrong Authorization header.
                .remove(KEY_PREFIX_TOKEN_TYPE + login)
                .apply();
        ServiceFactory.invalidateCache();
        NotificationsWorker.cancel(this);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(SettingsFragment.PREF_NAME, MODE_PRIVATE);
    }

    public static Gl4Application get() { return sInstance; }

    private String mCurrentProjectPath;

    public void setCurrentProjectPath(String pathWithNamespace) {
        mCurrentProjectPath = pathWithNamespace;
    }

    public String getCurrentProjectPath() {
        return mCurrentProjectPath;
    }

    public boolean isAuthorized() {
        return getAuthLogin() != null && getAuthToken() != null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null) return;
        if (key.equals(SettingsFragment.KEY_THEME)) {
            updateTheme(prefs);
        } else if (key.equals(KEY_ACTIVE_LOGIN)) {
            // Account switched — reschedule worker for new account's settings
            updateNotificationWorker(prefs);
        }
    }

    @NonNull
    @Override
    public androidx.work.Configuration getWorkManagerConfiguration() {
        return new androidx.work.Configuration.Builder().build();
    }
}
