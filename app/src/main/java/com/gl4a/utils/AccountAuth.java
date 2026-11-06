package com.gl4a.utils;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.gl4a.BuildConfig;
import com.gl4a.Gl4Application;
import com.gl4a.ServiceFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * THE way a request carries an account's token (#70): a token entered by hand goes in the
 * PRIVATE-TOKEN header, one from signing in with OAuth as "Authorization: Bearer". Every client
 * and every hand-built request uses it, so both kinds of account work everywhere.
 *
 * An OAuth access token lasts two hours. On a background thread, {@link #accessToken} renews it
 * shortly before then with its refresh token, and {@link #refreshAfterRejection} renews it when
 * GitLab rejects it sooner. If renewing fails (access revoked on GitLab), the token stays as it
 * is, GitLab answers 401, and the app asks to log in again as for any expired token (#311).
 */
public final class AccountAuth {
    private static final String TAG = Gl4Application.LOG_TAG;
    // Renewed this long before it runs out, so a request never leaves with a dying token
    private static final long RENEW_BEFORE_MS = 5 * 60 * 1000L;
    private static final Map<String, Object> sLocks = new HashMap<>();
    private static OkHttpClient sClient;

    private AccountAuth() {
    }

    public static boolean isOAuth(@Nullable String login) {
        return login != null && Gl4Application.TOKEN_TYPE_OAUTH.equals(
                Gl4Application.get().getTokenTypeForLogin(login));
    }

    /** The header a token goes in, by its kind. */
    public static String headerName(String tokenType) {
        return Gl4Application.TOKEN_TYPE_OAUTH.equals(tokenType)
                ? "Authorization" : "PRIVATE-TOKEN";
    }

    public static String headerValue(String token, String tokenType) {
        return Gl4Application.TOKEN_TYPE_OAUTH.equals(tokenType) ? "Bearer " + token : token;
    }

    /** Sets {@code token}'s header on a request, by its kind. */
    public static Request.Builder apply(Request.Builder builder, @Nullable String token,
            String tokenType) {
        if (token != null) {
            builder.header(headerName(tokenType), headerValue(token, tokenType));
        }
        return builder;
    }

    /**
     * Sets the account's header with its stored token, as it is, for requests built on the main
     * thread or outside OkHttp (web views, media players, downloads). Interceptors use
     * {@link #applyFresh}, which renews it first.
     */
    public static Request.Builder applyStored(Request.Builder builder, @Nullable String login) {
        if (login == null) return builder;
        Gl4Application app = Gl4Application.get();
        return apply(builder, app.getTokenForLogin(login), app.getTokenTypeForLogin(login));
    }

    /** {@link #applyStored} for the active account. */
    public static Request.Builder applyStored(Request.Builder builder) {
        return applyStored(builder, Gl4Application.get().getAuthLogin());
    }

    /** The active account's header as a map, for players and web views that take one. */
    public static Map<String, String> storedHeaders() {
        Map<String, String> headers = new HashMap<>();
        Gl4Application app = Gl4Application.get();
        String login = app.getAuthLogin();
        String token = login != null ? app.getTokenForLogin(login) : null;
        if (token != null) {
            String type = app.getTokenTypeForLogin(login);
            headers.put(headerName(type), headerValue(token, type));
        }
        return headers;
    }

    /** Sets the account's header with a token renewed if it's about to run out. */
    @WorkerThread
    public static Request.Builder applyFresh(Request.Builder builder, @Nullable String login) {
        if (login == null) return builder;
        return apply(builder, accessToken(login),
                Gl4Application.get().getTokenTypeForLogin(login));
    }

    /**
     * The account's token for a request: an OAuth one renewed first if it runs out within a few
     * minutes. Blocks while renewing; never call on the main thread.
     */
    @WorkerThread
    @Nullable
    public static String accessToken(String login) {
        Gl4Application app = Gl4Application.get();
        if (isOAuth(login)) {
            long expiresAt = app.getTokenExpiresAtForLogin(login);
            if (expiresAt > 0 && System.currentTimeMillis() > expiresAt - RENEW_BEFORE_MS) {
                renew(login, app.getTokenForLogin(login));
            }
        }
        return app.getTokenForLogin(login);
    }

    /**
     * After GitLab rejected {@code rejectedToken} (401): renews the account's OAuth token,
     * unless another request already did. Returns the token to retry with, or null when there's
     * nothing to retry (not OAuth, or renewing failed).
     */
    @WorkerThread
    @Nullable
    public static String refreshAfterRejection(String login, String rejectedToken) {
        if (!isOAuth(login)) return null;
        String renewed = renew(login, rejectedToken);
        return renewed != null && !renewed.equals(rejectedToken) ? renewed : null;
    }

    private static Object lockFor(String login) {
        synchronized (sLocks) {
            Object lock = sLocks.get(login);
            if (lock == null) {
                lock = new Object();
                sLocks.put(login, lock);
            }
            return lock;
        }
    }

    /**
     * Swaps the refresh token for new tokens, once per expiry: requests that waited on the lock
     * find the token already changed and use it. Returns the current token, or null if it
     * failed.
     */
    @Nullable
    private static String renew(String login, @Nullable String staleToken) {
        synchronized (lockFor(login)) {
            Gl4Application app = Gl4Application.get();
            String current = app.getTokenForLogin(login);
            if (current != null && !current.equals(staleToken)) {
                return current;
            }
            String refreshToken = app.getRefreshTokenForLogin(login);
            if (refreshToken == null) return null;
            String instance = app.getInstanceUrlForLogin(login);
            String clientId = OAuthLogin.clientIdFor(instance);
            if (clientId == null) return null;
            FormBody form = new FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("redirect_uri", OAuthLogin.redirectUri())
                    .build();
            try {
                Tokens tokens = postTokenRequest(instance, form);
                app.putOAuthTokens(login, tokens.accessToken,
                        tokens.refreshToken != null ? tokens.refreshToken : refreshToken,
                        tokens.expiresAt);
                ServiceFactory.invalidateCache();
                Log.d(TAG, "Renewed the OAuth token of an account");
                return tokens.accessToken;
            } catch (IOException | JSONException e) {
                Log.d(TAG, "Renewing an OAuth token failed", e);
                return null;
            }
        }
    }

    /** What GitLab's /oauth/token answers: the new tokens and when the access token ends. */
    public static final class Tokens {
        public final String accessToken;
        @Nullable public final String refreshToken;
        public final long expiresAt;

        Tokens(String accessToken, @Nullable String refreshToken, long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * Posts a form to {instance}/oauth/token and reads the tokens. Plain OkHttp without the
     * app's interceptors: they would add the account's own header to this request.
     */
    @WorkerThread
    public static Tokens postTokenRequest(String instanceUrl, FormBody form)
            throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(trimSlash(instanceUrl) + "/oauth/token")
                .post(form)
                .build();
        try (Response response = client().newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("OAuth token request failed: HTTP " + response.code());
            }
            JSONObject json = new JSONObject(text);
            String access = json.getString("access_token");
            String refresh = json.optString("refresh_token", null);
            long createdAt = json.optLong("created_at", System.currentTimeMillis() / 1000);
            long expiresIn = json.optLong("expires_in", 0);
            long expiresAt = expiresIn > 0 ? (createdAt + expiresIn) * 1000L : 0;
            return new Tokens(access, refresh, expiresAt);
        }
    }

    /**
     * Revokes an OAuth account's tokens on GitLab when it's removed from the app, so they stop
     * working at once. Best effort, in the background; removing never waits for it.
     */
    public static void revokeInBackground(String instanceUrl, @Nullable String accessToken,
            @Nullable String refreshToken) {
        String clientId = OAuthLogin.clientIdFor(instanceUrl);
        if (clientId == null) return;
        new Thread(() -> {
            for (String token : new String[] { refreshToken, accessToken }) {
                if (token == null) continue;
                FormBody form = new FormBody.Builder()
                        .add("client_id", clientId)
                        .add("token", token)
                        .build();
                Request request = new Request.Builder()
                        .url(trimSlash(instanceUrl) + "/oauth/revoke")
                        .post(form)
                        .build();
                try (Response response = client().newCall(request).execute()) {
                    Log.d(TAG, "Revoking an OAuth token: HTTP " + response.code());
                } catch (IOException e) {
                    Log.d(TAG, "Revoking an OAuth token failed", e);
                }
            }
        }, "OAuthRevoke").start();
    }

    private static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = ServiceFactory.getHttpClientBuilder().cache(null).build();
        }
        return sClient;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
