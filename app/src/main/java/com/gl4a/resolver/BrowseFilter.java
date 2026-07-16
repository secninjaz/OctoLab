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
        uri = normalizeUri(uri);

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
