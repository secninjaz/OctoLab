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
import com.gl4a.utils.OAuthLogin;
import com.gl4a.utils.RxUtils;

import io.reactivex.Single;

/**
 * The first screen without an account, and where gitlab.com's answer to "Sign in with
 * gitlab.com" arrives, also when adding an account later (#70, see {@link OAuthLogin}).
 */
public class GitLabLoginActivity extends BaseActivity implements
        View.OnClickListener, LoginModeChooserFragment.ParentCallback {
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
        boolean signingIn = OAuthLogin.isCallback(getIntent().getData());
        if (app.isAuthorized() && !signingIn) {
            goToToplevelActivity();
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

            if (signingIn) {
                setProgressShown(true);
            }
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (!handleIntent(intent)) {
            super.onNewIntent(intent);
        }
    }

    private boolean handleIntent(Intent intent) {
        Uri data = intent.getData();
        if (!OAuthLogin.isCallback(data)) {
            return false;
        }
        if (OAuthLogin.isCanceled(data)) {
            onLoginCanceled();
            leaveIfSignedIn();
            return true;
        }
        setProgressShown(true);
        OAuthLogin.complete(this, data)
                .compose(RxUtils::doInBackground)
                .subscribe(result -> {
                    boolean adding = Gl4Application.get().isAuthorized();
                    String key = OAuthLogin.addAccount(result);
                    if (adding) {
                        // The home screen switches to the new account, as after token login
                        startActivity(com.gl4a.activities.home.HomeActivity
                                .makeAccountAddedIntent(this, key));
                    } else {
                        goToToplevelActivity();
                    }
                    finish();
                }, error -> {
                    setProgressShown(false);
                    if (Gl4Application.get().isAuthorized()) {
                        // Adding an account: back to the app, saying why
                        android.widget.Toast.makeText(this, R.string.login_oauth_failed,
                                android.widget.Toast.LENGTH_LONG).show();
                        android.util.Log.d(Gl4Application.LOG_TAG, "OAuth sign-in failed", error);
                        leaveIfSignedIn();
                    } else {
                        handleLoadFailure(error);
                    }
                });
        return true;
    }

    /** Back to the app when an account is already signed in; this screen is for none. */
    private void leaveIfSignedIn() {
        if (Gl4Application.get().isAuthorized()) {
            goToToplevelActivity();
            finish();
        }
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
    public void onLoginStartOauth(String instanceUrl) {
        OAuthLogin.start(this, instanceUrl);
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
}
