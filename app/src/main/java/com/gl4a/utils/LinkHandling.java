package com.gl4a.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

import com.gl4a.R;

import java.util.Map;

/**
 * THE "open GitLab links in OctoLab" state and shortcut (#303). Since Android 12 an app opens a
 * site's links by default only if the site verifies it (#302, our own instance) or the user
 * allows it in App info → Open by default (e.g. gitlab.com, which can't verify third-party
 * apps). This checks the app's link hosts on the phone (no request) and opens that page.
 */
public final class LinkHandling {
    private static final String PREFS = "link_handling";
    private static final String KEY_PROMPTED = "prompted";

    private LinkHandling() {
    }

    /** Only Android 12+ asks the user; older versions offer the app when a link is opened. */
    public static boolean applies() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Whether every GitLab host the app handles opens in it by default. */
    public static boolean isEnabled(Context context) {
        if (!applies()) return true;
        DomainVerificationManager manager =
                context.getSystemService(DomainVerificationManager.class);
        if (manager == null) return true;
        try {
            DomainVerificationUserState state =
                    manager.getDomainVerificationUserState(context.getPackageName());
            if (state == null) return true;
            if (!state.isLinkHandlingAllowed()) return false;
            for (Map.Entry<String, Integer> host : state.getHostToStateMap().entrySet()) {
                if (host.getValue() == DomainVerificationUserState.DOMAIN_STATE_NONE) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /** Android's "Open by default" page for the app, else its App info. */
    public static void openSettings(Context context) {
        String uri = "package:" + context.getPackageName();
        try {
            context.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                    Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse(uri)));
        }
    }

    /**
     * Once, on a first start where GitLab links don't open in the app: asks whether they
     * should, with a shortcut to the setting. Asked once only, whatever the answer.
     */
    public static void promptOnce(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PROMPTED, false) || isEnabled(activity)) return;
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.link_handling_prompt_title)
                .setMessage(R.string.link_handling_prompt_message)
                .setPositiveButton(R.string.link_handling_open_settings,
                        (dialog, which) -> openSettings(activity))
                .setNegativeButton(R.string.link_handling_not_now, null)
                .show();
    }
}
