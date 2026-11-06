package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.gl4a.Gl4Application;
import com.gl4a.gitlab.model.GitLabUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * THE git email → GitLab account answers (#322): which account GitLab linked an email's
 * commits to, learnt from commit lists, taps on an author and Contributors' lookups. Kept on
 * the phone per account (its server's answers), for as long as the account is signed in, and
 * deleted when it's logged out. Only the link is kept (id, username, name, avatar address);
 * pictures stay in the shared picture cache. At most {@link #MAX} per account, the least
 * recently used dropped first.
 */
public final class GitAuthorLinks {
    private static final String PREFS = "git_author_links";
    private static final int MAX = 2000;
    // Per account key: email → link, in use order (least recent first)
    private static final Map<String, LinkedHashMap<String, GitLabUser>> sLinks = new LinkedHashMap<>();

    private GitAuthorLinks() {
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT).trim();
    }

    private static SharedPreferences prefs() {
        return Gl4Application.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The active account's links, loaded from the phone the first time. */
    private static synchronized LinkedHashMap<String, GitLabUser> links(@Nullable String account) {
        if (account == null) return null;
        LinkedHashMap<String, GitLabUser> links = sLinks.get(account);
        if (links != null) return links;
        links = new LinkedHashMap<>(16, 0.75f, true);
        String stored = prefs().getString(account, null);
        if (stored != null) {
            try {
                JSONObject json = new JSONObject(stored);
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String email = keys.next();
                    JSONObject u = json.getJSONObject(email);
                    GitLabUser user = GitLabUser.create(u.getString("username"), u.optLong("id"));
                    user.name = u.optString("name", null);
                    user.avatarUrl = u.optString("avatar_url", null);
                    links.put(email, user);
                }
            } catch (JSONException e) {
                links.clear();
            }
        }
        sLinks.put(account, links);
        return links;
    }

    /** The account GitLab linked {@code email} to, for the active account, or null. */
    @Nullable
    public static synchronized GitLabUser get(@Nullable String email) {
        if (email == null) return null;
        LinkedHashMap<String, GitLabUser> links = links(Gl4Application.get().getAuthLogin());
        return links != null ? links.get(normalize(email)) : null;
    }

    /** Keeps the link for the active account, on the phone until it's logged out. */
    public static synchronized void put(@Nullable String email, @Nullable GitLabUser user) {
        if (email == null || user == null || user.login() == null) return;
        String account = Gl4Application.get().getAuthLogin();
        LinkedHashMap<String, GitLabUser> links = links(account);
        if (links == null) return;
        links.put(normalize(email), user);
        while (links.size() > MAX) {
            links.remove(links.keySet().iterator().next());
        }
        save(account, links);
    }

    private static void save(String account, LinkedHashMap<String, GitLabUser> links) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, GitLabUser> e : links.entrySet()) {
                GitLabUser u = e.getValue();
                JSONObject entry = new JSONObject()
                        .put("username", u.login())
                        .put("id", u.id());
                if (u.name != null) entry.put("name", u.name);
                if (u.avatarUrl() != null) entry.put("avatar_url", u.avatarUrl());
                json.put(e.getKey(), entry);
            }
        } catch (JSONException e) {
            return;
        }
        prefs().edit().putString(account, json.toString()).apply();
    }

    /** Deletes an account's links, when it's logged out. */
    public static synchronized void deleteAccount(@Nullable String account) {
        if (account == null) return;
        sLinks.remove(account);
        prefs().edit().remove(account).apply();
    }
}
