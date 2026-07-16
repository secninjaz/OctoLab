/*
 * Copyright 2011 Azwan Adli Abdullah — ported to GitLab by SecNinjaz OctoLab team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.gl4a.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import com.gl4a.utils.ActivityResultHelpers;
import com.google.android.material.appbar.AppBarLayout;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;

import com.gl4a.BaseActivity;
import com.gl4a.BuildConfig;
import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.fragment.LoginModeChooserFragment;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabOAuthService;
import com.gl4a.gitlab.service.GitLabUserService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.IntentUtils;
import com.gl4a.utils.RxUtils;

import io.reactivex.Single;

/**
 * GitLab OAuth login activity (formerly Github4AndroidActivity).
 * Handles the OAuth 2.0 PKCE flow for GitLab instances.
 */
public class GitLabLoginActivity extends BaseActivity implements
        View.OnClickListener, LoginModeChooserFragment.ParentCallback {
    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_CODE = "code";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_CALLBACK_URI = "redirect_uri";
    private static final String PARAM_RESPONSE_TYPE = "response_type";

    private static final Uri CALLBACK_URI = Uri.parse("gl4a://oauth");

    private View mContent;
    private View mProgress;

    private final ActivityResultLauncher<Void> mSettingsLauncher = registerForActivityResult(
            new ActivityResultHelpers.StartSettingsContract(),
            themeChange -> {
                if (themeChange) {
                    Intent intent = new Intent(getIntent());
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    finish();
                }
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Gl4Application app = Gl4Application.get();
        if (app.isAuthorized()) {
            if (!handleIntent(getIntent())) {
                goToToplevelActivity();
            }
            finish();
        } else {
            setContentView(R.layout.main);

            AppBarLayout abl = findViewById(R.id.header);
            abl.setEnabled(false);

            FrameLayout contentContainer = (FrameLayout) findViewById(R.id.content).getParent();
            contentContainer.setForeground(null);

            findViewById(R.id.login_button).setOnClickListener(this);
            mContent = findViewById(R.id.welcome_container);
            mProgress = findViewById(R.id.login_progress_container);

            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (!handleIntent(intent)) {
            super.onNewIntent(intent);
        }
    }

    private static java.util.Map<String, String> buildTokenBody(String code, String redirectUri) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("client_id", BuildConfig.CLIENT_ID);
        body.put("client_secret", BuildConfig.CLIENT_SECRET);
        body.put("code", code);
        body.put("grant_type", "authorization_code");
        body.put("redirect_uri", redirectUri);
        return body;
    }

    private boolean handleIntent(Intent intent) {
        Uri data = intent.getData();
        if (data != null
                && data.getScheme().equals(CALLBACK_URI.getScheme())
                && data.getHost().equals(CALLBACK_URI.getHost())) {
            final String code = data.getQueryParameter(PARAM_CODE);
            if (code == null) {
                onLoginCanceled();
                return true;
            }

            GitLabOAuthService service = ServiceFactory.getOAuthService(GitLabOAuthService.class);
            service.getToken(buildTokenBody(code, CALLBACK_URI.toString()))
                    .map(ApiHelpers::throwOnFailure)
                    .flatMap(token -> {
                        GitLabUserService userService = ServiceFactory.get(GitLabUserService.class, true,
                                null, token.accessToken(), null);
                        Single<GitLabUser> userSingle = userService.getCurrentUser()
                                .map(ApiHelpers::throwOnFailure);
                        return Single.zip(Single.just(token), userSingle,
                                (t, user) -> Pair.create(t.accessToken(), user));
                    })
                    .compose(upstream -> RxUtils.doInBackground(upstream))
                    .subscribe(pair -> {
                        // OAuth2 flow — store with OAuth token type so Bearer header is used
                        Gl4Application.get().addAccount(pair.second, pair.first,
                                Gl4Application.TOKEN_TYPE_OAUTH);
                        goToToplevelActivity();
                        finish();
                    }, this::handleLoadFailure);
            return true;
        }
        return false;
    }

    @Override
    protected int getLeftNavigationDrawerMenuResource() {
        return R.menu.home_nav_drawer;
    }

    @IdRes
    protected int getInitialLeftDrawerSelection(Menu menu) {
        menu.setGroupCheckable(R.id.navigation, false, false);
        menu.setGroupCheckable(R.id.explore, false, false);
        menu.setGroupVisible(R.id.my_items, false);
        return super.getInitialLeftDrawerSelection(menu);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        super.onNavigationItemSelected(item);
        switch (item.getItemId()) {
            case R.id.settings:
                mSettingsLauncher.launch(null);
                return true;
            case R.id.search:
                startActivity(SearchActivity.makeIntent(this));
                return true;
            case R.id.bookmarks:
                startActivity(new Intent(this, BookmarkListActivity.class));
                return true;
        }
        return false;
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return false;
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.login_button) {
            LoginModeChooserFragment.newInstance().show(getSupportFragmentManager(), "login");
            setProgressShown(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (mProgress.getVisibility() == View.VISIBLE) {
            setProgressShown(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onLoginStartOauth() {
        launchOauthLogin(this);
    }

    @Override
    public void onLoginFinished(String token, GitLabUser user) {
        Gl4Application.get().addAccount(user, token);
        goToToplevelActivity();
        finish();
    }

    @Override
    public void onLoginFailed(Throwable error) {
        handleLoadFailure(error);
        setProgressShown(false);
    }

    @Override
    public void onLoginCanceled() {
        setProgressShown(false);
    }

    private void setProgressShown(boolean show) {
        mContent.setVisibility(show ? View.GONE : View.VISIBLE);
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public static void launchOauthLogin(Activity activity) {
        Gl4Application app = Gl4Application.get();
        String instanceBase = app.getInstanceBaseUrl();
        Uri uri = Uri.parse(instanceBase + "/oauth/authorize")
                .buildUpon()
                .appendQueryParameter(PARAM_CLIENT_ID, BuildConfig.CLIENT_ID)
                .appendQueryParameter(PARAM_RESPONSE_TYPE, "code")
                .appendQueryParameter(PARAM_SCOPE, "read_user api read_repository")
                .appendQueryParameter(PARAM_CALLBACK_URI, CALLBACK_URI.toString())
                .build();
        IntentUtils.openInCustomTabOrBrowser(activity, uri);
    }
}
