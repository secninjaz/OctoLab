package com.gl4a.utils;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.gl4a.Gl4Application;
import com.gl4a.gitlab.model.GitLabUser;

import java.util.ArrayList;
import java.util.List;

/**
 * THE answer to "which account is this, and is this me" (#318). An account is a GitLab
 * username on a server. The app keeps each under a key: its username, as always, or
 * "username@host" when another account has the same username on another server.
 *
 * Use {@link Account#key} for anything the app keeps per account (settings, tokens, inbox,
 * bookmarks), and {@link Account#username} or {@link #isMe} for anything that means "me" on
 * GitLab (own comments and reactions, own profile and projects, reviewer queries). For every
 * account added before #318, key and username are the same.
 */
public final class Accounts {
    private Accounts() {
    }

    /** One account: its key, its GitLab username and its server. */
    public static final class Account {
        public final String key;
        public final String username;
        public final String instanceUrl;

        Account(String key, String username, String instanceUrl) {
            this.key = key;
            this.username = username;
            this.instanceUrl = instanceUrl;
        }

        @Nullable
        public String host() {
            return Uri.parse(instanceUrl).getHost();
        }

        /**
         * As account lists name it: the username, with the server when another account has
         * the same username.
         */
        public String label() {
            for (Account other : all()) {
                if (!other.key.equals(key) && other.username.equalsIgnoreCase(username)) {
                    return username + " · " + host();
                }
            }
            return username;
        }
    }

    /** The account with this key, or null. */
    @Nullable
    public static Account of(@Nullable String key) {
        if (key == null) return null;
        Gl4Application app = Gl4Application.get();
        return new Account(key, app.getUsernameForLogin(key), app.getInstanceUrlForLogin(key));
    }

    /** The active account, or null when none is signed in. */
    @Nullable
    public static Account active() {
        return of(Gl4Application.get().getAuthLogin());
    }

    public static List<Account> all() {
        List<Account> accounts = new ArrayList<>();
        for (String key : Gl4Application.get().getAllLogins()) {
            accounts.add(of(key));
        }
        return accounts;
    }

    /** The active account's GitLab username, or null. */
    @Nullable
    public static String myUsername() {
        Account account = active();
        return account != null ? account.username : null;
    }

    /** Whether {@code user}, as GitLab sent it for the active account, is that account. */
    public static boolean isMe(@Nullable GitLabUser user) {
        return user != null && isMe(user.login());
    }

    /** Whether {@code username}, from the active account's server, is that account's. */
    public static boolean isMe(@Nullable String username) {
        return ApiHelpers.loginEquals(username, myUsername());
    }

    /** Whether {@code user}, as GitLab sent it for {@code account}, is that account. */
    public static boolean isAccount(@Nullable Account account, @Nullable GitLabUser user) {
        return account != null && user != null
                && ApiHelpers.loginEquals(user.login(), account.username);
    }

    /**
     * The key for {@code username} on {@code instanceUrl}: the account's own if it's already
     * added there, else the username, or "username@host" when that's taken on another server.
     */
    public static String keyFor(String username, String instanceUrl) {
        String host = Uri.parse(instanceUrl).getHost();
        boolean usernameTaken = false;
        for (Account account : all()) {
            if (!account.username.equalsIgnoreCase(username)) continue;
            if (host != null && host.equalsIgnoreCase(account.host())) {
                return account.key;
            }
            usernameTaken = true;
        }
        return usernameTaken ? username + "@" + host : username;
    }
}
