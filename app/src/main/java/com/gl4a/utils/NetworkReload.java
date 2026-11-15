package com.gl4a.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE way a screen showing a connection error loads again when the phone's network comes back
 * or changes, e.g. once a VPN is up (#326): watched only while the screen is on view, once per
 * change, and only for connection errors, never for GitLab refusing or bad data. An error that
 * arrives just after a change (the request failed while the VPN was still connecting) loads
 * again once too.
 */
public final class NetworkReload {
    /** What the screen does about it. */
    public interface Screen {
        /** Whether it shows a connection error now. */
        boolean showsConnectionError();

        /** Loads again, as its Retry button does. */
        void reload();
    }

    // A failure this soon after a change may be the change's: one more try, a moment later
    private static final long RECENT_CHANGE_MS = 15_000;
    private static final long SETTLE_MS = 3_000;

    private final Context mContext;
    private final Screen mScreen;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private ConnectivityManager.NetworkCallback mCallback;
    @Nullable
    private Network mNetwork;
    private long mChangedAt;
    private boolean mRetriedAfterChange;

    /**
     * Only keeps {@code context}: a screen makes its helper while it's being built, before it
     * has a context to ask anything of (asking crashed every screen, 326).
     */
    public NetworkReload(Context context, Screen screen) {
        mContext = context;
        mScreen = screen;
    }

    private ConnectivityManager connectivity() {
        return mContext.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    /** Whether {@code error} is the network's, not GitLab's answer. */
    public static boolean isConnectionError(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.IOException) return true;
        }
        return false;
    }

    /** The screen came into view. */
    public void start() {
        if (mCallback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm = connectivity();
        if (cm == null) return;
        mNetwork = cm.getActiveNetwork();
        mCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mHandler.post(() -> onNetwork(network));
            }
        };
        try {
            cm.registerDefaultNetworkCallback(mCallback);
        } catch (RuntimeException e) {
            // Android limits how many callbacks an app holds; the Retry button stays
            mCallback = null;
        }
    }

    /** The screen went out of view. */
    public void stop() {
        mHandler.removeCallbacksAndMessages(null);
        if (mCallback == null) return;
        ConnectivityManager cm = connectivity();
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(mCallback);
            } catch (RuntimeException ignored) {
                // Not registered any more
            }
        }
        mCallback = null;
    }

    private void onNetwork(Network network) {
        // Android reports the network already in use at once: not a change
        if (network.equals(mNetwork)) return;
        mNetwork = network;
        mChangedAt = SystemClock.elapsedRealtime();
        mRetriedAfterChange = false;
        if (mScreen.showsConnectionError()) reloadAfterSettling();
    }

    /** The screen now shows {@code error}. */
    public void onErrorShown(@Nullable Throwable error) {
        if (!isConnectionError(error) || mCallback == null || mChangedAt == 0
                || mRetriedAfterChange
                || SystemClock.elapsedRealtime() - mChangedAt > RECENT_CHANGE_MS) {
            return;
        }
        reloadAfterSettling();
    }

    private void reloadAfterSettling() {
        mRetriedAfterChange = true;
        mHandler.removeCallbacksAndMessages(null);
        mHandler.postDelayed(() -> {
            if (mScreen.showsConnectionError()) mScreen.reload();
        }, SETTLE_MS);
    }
}
