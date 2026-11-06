package com.gl4a.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.gl4a.BuildConfig;
import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabUser;
import com.gl4a.gitlab.service.GitLabUserService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import io.reactivex.Single;
import okhttp3.FormBody;

/**
 * Signing in with OAuth (#70): gitlab.com, and in internal builds the internal instance; OAuth
 * 2 with PKCE, as a public app without a secret.
 *
 * {@link #start} keeps two one-time random values, the PKCE verifier and the state, and opens
 * gitlab.com's own sign-in and "Authorize OctoLab?" page in the browser; the app never sees the
 * password. gitlab.com sends the answer back to {@code <application id>://oauth}, registered
 * on gitlab.com for each variant of the app. {@link #complete} checks the state is the one
 * sent, then swaps the code and the verifier for the tokens and asks who signed in.
 */
public final class OAuthLogin {
    private static final String GITLAB_COM = Gl4Application.DEFAULT_INSTANCE;
    private static final String SCOPE = "api";
    private static final String PREFS = "oauth_pending";
    private static final String KEY_STATE = "state";
    private static final String KEY_VERIFIER = "verifier";
    private static final String KEY_INSTANCE = "instance";

    private OAuthLogin() {
    }

    /** {@code com.secninjaz.octolab[.internal][.debug]://oauth}, one per variant. */
    public static String redirectUri() {
        return BuildConfig.APPLICATION_ID + "://oauth";
    }

    /** A server's host as typed or stored: "gitlab.com" for an empty field. */
    @Nullable
    public static String hostOf(@Nullable String instanceUrl) {
        if (instanceUrl == null || instanceUrl.trim().isEmpty()) return "gitlab.com";
        String url = instanceUrl.trim();
        return Uri.parse(url.contains("://") ? url : "https://" + url).getHost();
    }

    /**
     * OctoLab's application on this server, or null where it has none: gitlab.com, and in
     * internal builds the internal instance, from the CI settings (#70). Other servers keep
     * token login, each needing its own registration.
     */
    @Nullable
    public static String clientIdFor(@Nullable String instanceUrl) {
        String host = hostOf(instanceUrl);
        if (host == null) return null;
        if ("gitlab.com".equalsIgnoreCase(host)) return BuildConfig.GITLAB_COM_CLIENT_ID;
        if (!BuildConfig.INTERNAL_OAUTH_CLIENT_ID.isEmpty()
                && host.equalsIgnoreCase(BuildConfig.INTERNAL_OAUTH_HOST)) {
            return BuildConfig.INTERNAL_OAUTH_CLIENT_ID;
        }
        return null;
    }

    /** Whether OAuth sign-in is offered for this server. */
    public static boolean isAvailableFor(@Nullable String instanceUrl) {
        return clientIdFor(instanceUrl) != null;
    }

    /** The server's own sign-in page; the answer comes back to {@link #isCallback}. */
    public static void start(Activity activity, String instanceUrl) {
        String clientId = clientIdFor(instanceUrl);
        if (clientId == null) return;
        String instance = "https://" + hostOf(instanceUrl);
        String verifier = randomString(32);
        String state = randomString(16);
        prefs(activity).edit()
                .putString(KEY_VERIFIER, verifier)
                .putString(KEY_STATE, state)
                .putString(KEY_INSTANCE, instance)
                .commit();
        Uri uri = Uri.parse(instance + "/oauth/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challengeFor(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .build();
        IntentUtils.openInCustomTabOrBrowser(activity, uri);
    }

    public static boolean isCallback(@Nullable Uri uri) {
        return uri != null && BuildConfig.APPLICATION_ID.equals(uri.getScheme())
                && "oauth".equals(uri.getHost());
    }

    /** The user said no on gitlab.com, or closed the page there. */
    public static boolean isCanceled(Uri callback) {
        return callback.getQueryParameter("code") == null;
    }

    /** The account that signed in, on which server, with its tokens. */
    public static final class Result {
        public final GitLabUser user;
        public final String instance;
        public final AccountAuth.Tokens tokens;

        Result(GitLabUser user, String instance, AccountAuth.Tokens tokens) {
            this.user = user;
            this.instance = instance;
            this.tokens = tokens;
        }
    }

    /**
     * Finishes the sign-in from gitlab.com's answer: the state must be the one this app sent
     * (else it's someone else's answer and is refused), then the code and verifier become the
     * tokens, used once to ask who signed in. The one-time values are forgotten either way.
     */
    public static Single<Result> complete(Context context, Uri callback) {
        String[] instance = new String[1];
        return Single.fromCallable(() -> {
            SharedPreferences prefs = prefs(context);
            String state = prefs.getString(KEY_STATE, null);
            String verifier = prefs.getString(KEY_VERIFIER, null);
            instance[0] = prefs.getString(KEY_INSTANCE, null);
            prefs.edit().clear().commit();
            String code = callback.getQueryParameter("code");
            String clientId = clientIdFor(instance[0]);
            if (state == null || verifier == null || code == null || clientId == null
                    || !state.equals(callback.getQueryParameter("state"))) {
                throw new IllegalStateException("This sign-in wasn't started here");
            }
            FormBody form = new FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", redirectUri())
                    .add("code_verifier", verifier)
                    .build();
            return AccountAuth.postTokenRequest(instance[0], form);
        }).flatMap(tokens -> ServiceFactory.getForToken(GitLabUserService.class,
                        instance[0] + "/api/v4/", tokens.accessToken,
                        Gl4Application.TOKEN_TYPE_OAUTH)
                .getCurrentUser()
                .map(ApiHelpers::throwOnFailure)
                .map(user -> new Result(user, instance[0], tokens)));
    }

    /** Adds the signed-in account to the app, or renews its token if it's already there. */
    /** Returns the account's key (#318). */
    public static String addAccount(Result result) {
        Gl4Application app = Gl4Application.get();
        String key = app.addAccount(result.user, result.tokens.accessToken,
                Gl4Application.TOKEN_TYPE_OAUTH, result.instance);
        app.putOAuthTokens(key, result.tokens.accessToken,
                result.tokens.refreshToken, result.tokens.expiresAt);
        return key;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String randomString(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return base64Url(random);
    }

    private static String challengeFor(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return base64Url(sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
