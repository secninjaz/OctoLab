package com.gl4a.resolver;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import com.gl4a.utils.IntentUtils;

/**
 * Transparent activity that intercepts URLs (both https://&lt;gitlabHost&gt; deep links
 * and gl4a:// scheme URIs) and routes them to the appropriate in-app activity.
 *
 * The custom scheme is {@code gl4a://} (previously gh4a://).
 * GitLab web URLs follow the pattern:
 *   https://&lt;host&gt;/&lt;namespace&gt;/&lt;project&gt;/-/&lt;resource&gt;/...
 *
 * NOTE: Android intent filters do not support wildcard hostnames. The manifest registers only
 * gitlab.com as a default host. Users on self-hosted GitLab instances will not have external
 * https:// links intercepted by this activity. Use the gl4a:// scheme from notifications to
 * reliably deep-link into the app regardless of host.
 */
public class BrowseFilter extends AppCompatActivity {
    private static final String EXTRA_INITIAL_COMMENT = "initial_comment";

    /** Custom URI scheme used for in-app deep links (e.g. from notifications). */
    public static final String GL4A_SCHEME = "gl4a";

    public static Intent makeRedirectionIntent(Context context, Uri uri,
            IntentUtils.InitialCommentMarker initialComment) {
        Intent intent = new Intent(context, BrowseFilter.class);
        intent.setData(uri);
        intent.putExtra(EXTRA_INITIAL_COMMENT, initialComment);
        return intent;
    }

    public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.TransparentTheme);

        super.onCreate(savedInstanceState);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        // Translate gl4a:// URIs to https:// GitLab URLs for uniform parsing.
        final Uri normalized = normalizeUri(uri);

        // A link only routes in-app if its host matches the currently active account's
        // instance (see LinkParser). Switch to a matching account first — automatically
        // if only one is logged in for that host, or by asking if more than one is —
        // so links for a non-active instance still open in-app instead of bouncing to
        // the browser just because a different account happened to be active.
        resolveAccountForHost(normalized.getHost(), () -> proceedWithUri(normalized));
    }

    private void resolveAccountForHost(String host, Runnable proceed) {
        java.util.List<String> matches = Gl4Application.get().getLoginsForInstanceHost(host);

        if (matches.isEmpty()) {
            // No logged-in account for this host — LinkParser will fall back to the browser.
            proceed.run();
            return;
        }

        if (matches.size() == 1) {
            String only = matches.get(0);
            if (!only.equals(Gl4Application.get().getAuthLogin())) {
                Gl4Application.get().setActiveLogin(only);
            }
            proceed.run();
            return;
        }

        // Multiple accounts on this instance — always ask which one to use, even if
        // one of them is already active, since the user may want a specific other one.
        String[] logins = matches.toArray(new String[0]);
        CharSequence[] labels = new CharSequence[logins.length];
        for (int i = 0; i < logins.length; i++) {
            String name = Gl4Application.get().getNameForLogin(logins[i]);
            labels[i] = !com.gl4a.utils.StringUtils.isBlank(name)
                    ? name + " (@" + logins[i] + ")" : "@" + logins[i];
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.choose_account_for_link)
                .setItems(labels, (dialog, which) -> {
                    Gl4Application.get().setActiveLogin(logins[which]);
                    proceed.run();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void proceedWithUri(Uri uri) {
        int flags = getIntent().getFlags() & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
        if ((flags & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)) != 0) {
            flags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        }
        IntentUtils.InitialCommentMarker initialComment =
                getIntent().getParcelableExtra(EXTRA_INITIAL_COMMENT);

        LinkParser.ParseResult result = LinkParser.parseUri(this, uri, initialComment);
        if (result == null) {
            IntentUtils.launchBrowser(this, uri, flags);
            finish();
            return;
        }

        if (result.intent != null) {
            startActivity(result.intent.setFlags(flags));
            finish();
            return;
        }

        result.loadTask.setIntentFlags(flags);
        result.loadTask.setCompletionCallback(this::finish);
        result.loadTask.execute();
    }

    /**
     * Converts a {@code gl4a://} URI to the equivalent GitLab HTTPS URL so that
     * {@link LinkParser} can process it with a single code path.
     *
     * gl4a://open?url=https%3A%2F%2Fgitlab.com%2Fowner%2Frepo%2F... is one form;
     * gl4a://gitlab.com/owner/repo/-/issues/1 is another.
     */
    private Uri normalizeUri(Uri uri) {
        if (!GL4A_SCHEME.equals(uri.getScheme())) {
            return uri;
        }
        // If the URI carries an explicit "url" query parameter, decode and use that.
        String urlParam = uri.getQueryParameter("url");
        if (urlParam != null) {
            return Uri.parse(urlParam);
        }
        // Otherwise reconstruct as https using the GitLab instance host.
        String instanceHost = Uri.parse(Gl4Application.get().getInstanceUrl()).getHost();
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            host = instanceHost != null ? instanceHost : "gitlab.com";
        }
        return uri.buildUpon().scheme("https").authority(host).build();
    }
}
