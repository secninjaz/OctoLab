package com.gl4a.utils;
import com.gl4a.gitlab.model.GitLabUser;

import java.io.IOException;
import java.util.ArrayList;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.collection.LongSparseArray;
import androidx.collection.LruCache;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.ImageView;

import com.gl4a.ServiceFactory;

import okhttp3.OkHttpClient;

public class AvatarHandler {
    private static final String TAG = "GravatarHandler";

    private static final int MAX_CACHED_IMAGE_SIZE = 60; /* dp - maximum gravatar view size used */

    private static LruCache<Long, Bitmap> sCache;
    private static int sNextRequestId = 1;

    private static class Request {
        long id;
        String url;
        String email;        // used to call /api/v4/avatar?email= if primary URL fails
        String fallbackUrl;  // Gravatar URL, tried if Avatar API also fails
        boolean apiFirst;    // true → try GitLab Avatar API before url (for email-only lookups)
        long projectId;      // > 0 → fetch avatar via GET /projects/{id} (project avatar path)
        ArrayList<ViewDelegate> views;
    }
    private static final LongSparseArray<Request> sRequests = new LongSparseArray<>();
    private static int sMaxImageSizePx = -1;

    // Resolved GitLab users keyed by commit author email — populated by fetchUserAvatarByEmail
    // so avatar clicks can open the correct profile without re-fetching.
    private static final java.util.Map<String, GitLabUser> sEmailUserCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Returns the resolved GitLabUser for a commit author email, or null if not yet loaded. */
    public static GitLabUser getCachedUserForEmail(String email) {
        if (email == null || email.isEmpty()) return null;
        return sEmailUserCache.get(email.toLowerCase(java.util.Locale.ROOT).trim());
    }

    private static final int MSG_LOAD = 1;
    private static final int MSG_LOADED = 2;
    private static final int MSG_DESTROY = 3;

    private static HandlerThread sWorkerThread = null;
    private static Handler sWorkerHandler = null;

    private static final Handler sHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOADED:
                    processResult(msg.arg1, (Bitmap) msg.obj);
                    if (sRequests.size() == 0) {
                        sendEmptyMessageDelayed(MSG_DESTROY, 3000);
                    }
                    break;
                case MSG_DESTROY:
                    shutdownWorker();
                    break;
            }
        }

        private void processResult(long requestId, Bitmap bitmap) {
            final Request request = sRequests.get(requestId);
            if (request != null && bitmap != null) {
                synchronized (AvatarHandler.class) {
                    sCache.put(request.id, bitmap);
                }

                for (ViewDelegate view : request.views) {
                    applyAvatarToView(view, bitmap);
                }
            }
            sRequests.remove(requestId);
        }
    };

    public static void assignAvatar(ImageView view, GitLabUser user) {
        if (user == null) {
            assignAvatarInternal(new ImageViewDelegate(view), null, 0, null, null);
            return;
        }
        if (user.id() == 0L) {
            // No user ID — use email-based lookup (GitLab Avatar API → Gravatar) if available.
            if (user.email != null && !user.email.isEmpty()) {
                assignAvatarByEmail(view, user.name != null ? user.name : user.login(), user.email);
            } else {
                assignAvatarInternal(new ImageViewDelegate(view), null, 0, null, null);
            }
            return;
        }
        // Pass email so we can fall back to Gravatar if the instance avatar fails.
        assignAvatarInternal(new ImageViewDelegate(view), user.login(), user.id(),
                user.avatarUrl(), user.email);
    }

    public static void assignAvatar(ImageView view, String userName, long userId, String url) {
        assignAvatarInternal(new ImageViewDelegate(view), userName, userId, url, null);
    }

    /**
     * Loads an avatar for a git author who has no GitLab user account (e.g. commit authors).
     * Order: GitLab Avatar API (/api/v4/avatar?email=) → Gravatar → initials.
     * The API is tried first because it returns the actual instance profile picture; Gravatar
     * is the fallback for authors who have no account on this instance.
     */
    public static void assignAvatarByEmail(ImageView view, String userName, String email) {
        if (email == null || email.trim().isEmpty()) {
            view.setImageDrawable(new DefaultAvatarDrawable(userName, null));
            return;
        }
        // Derive a stable, always-positive cache key so the userId <= 0 guard is bypassed.
        long cacheId = ((long) email.toLowerCase(java.util.Locale.ROOT).trim().hashCode()
                & 0x7FFF_FFFFL) + 1L;

        ImageViewDelegate delegate = new ImageViewDelegate(view);
        removeOldRequest(delegate);

        Bitmap cached = loadBitmapFromCache(view.getContext(), cacheId);
        if (cached != null) {
            applyAvatarToView(delegate, cached);
            return;
        }

        view.setImageDrawable(new DefaultAvatarDrawable(userName, email));

        Request existing = getRequestForId(cacheId);
        if (existing != null) {
            existing.views.add(delegate);
            return;
        }

        int requestId = sNextRequestId++;
        Request request = new Request();
        request.id = cacheId;
        request.url = buildGravatarUrl(email); // Gravatar as fallback when API fails
        request.email = email;
        request.apiFirst = true;
        request.fallbackUrl = null;
        request.views = new ArrayList<>();
        request.views.add(delegate);
        sRequests.put(requestId, request);

        sHandler.removeMessages(MSG_DESTROY);
        if (sWorkerThread == null) {
            sWorkerThread = new HandlerThread("GravatarLoader");
            sWorkerThread.start();
            sWorkerHandler = new WorkerHandler(sWorkerThread.getLooper());
        }
        sWorkerHandler.obtainMessage(MSG_LOAD, requestId, 0, request.url).sendToTarget();
    }

    /**
     * Loads the project avatar for the To-do list header row.
     * Fetches avatar_url via GET /projects/{id} (Todos API omits it) and caches by project ID.
     */
    public static void assignAvatarForProject(ImageView view, String projectName, long projectId) {
        if (projectId <= 0) {
            view.setImageDrawable(new DefaultAvatarDrawable(projectName, null));
            return;
        }
        // Use a large negative offset so project IDs don't collide with user IDs in the cache.
        long cacheId = Long.MIN_VALUE / 2 + projectId;

        ImageViewDelegate delegate = new ImageViewDelegate(view);
        removeOldRequest(delegate);

        Bitmap cached = loadBitmapFromCache(view.getContext(), cacheId);
        if (cached != null) {
            applyAvatarToView(delegate, cached);
            return;
        }

        view.setImageDrawable(new DefaultAvatarDrawable(projectName, projectId));

        Request existing = getRequestForId(cacheId);
        if (existing != null) {
            existing.views.add(delegate);
            return;
        }

        int requestId = sNextRequestId++;
        Request request = new Request();
        request.id = cacheId;
        request.projectId = projectId;
        request.apiFirst = true; // worker checks projectId > 0 and uses project path
        request.views = new ArrayList<>();
        request.views.add(delegate);
        sRequests.put(requestId, request);

        sHandler.removeMessages(MSG_DESTROY);
        if (sWorkerThread == null) {
            sWorkerThread = new HandlerThread("GravatarLoader");
            sWorkerThread.start();
            sWorkerHandler = new WorkerHandler(sWorkerThread.getLooper());
        }
        sWorkerHandler.obtainMessage(MSG_LOAD, requestId, 0, (Object) null).sendToTarget();
    }

    public static void assignAvatar(Context context, MenuItem item,
            String userName, long userId) {
        assignAvatarInternal(new MenuItemDelegate(context, item), userName, userId, null, null);
    }

    public static Bitmap loadUserAvatarSynchronously(Context context, GitLabUser user) {
        if (user == null) {
            return null;
        }
        Bitmap cachedBitmap = loadBitmapFromCache(context, user.id());
        if (cachedBitmap != null) {
            return cachedBitmap;
        }
        try {
            String avatarUrl = makeUrl(user.avatarUrl(), user.id());
            if (avatarUrl == null) return null;
            Bitmap bitmap = fetchBitmap(avatarUrl);
            if (bitmap != null) {
                synchronized (AvatarHandler.class) {
                    sCache.put(user.id(), bitmap);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap loadBitmapFromCache(Context context, long id) {
        synchronized (AvatarHandler.class) {
            if (sCache == null) {
                initialize(context);
            }
            return sCache.get(id);
        }
    }

    private static void assignAvatarInternal(ViewDelegate view,
            String userName, long userId, String url, String email) {
        removeOldRequest(view);

        Bitmap bitmap = loadBitmapFromCache(view.getContext(), userId);
        if (bitmap != null) {
            applyAvatarToView(view, bitmap);
            return;
        }

        view.setDrawable(new DefaultAvatarDrawable(userName, userId));
        if (userId <= 0) {
            return;
        }

        Request request = getRequestForId(userId);
        if (request != null) {
            request.views.add(view);
            return;
        }

        String resolvedUrl = makeUrl(url, userId);
        // Gravatar fallback: used when the primary URL is unavailable or auth-restricted.
        String gravatarFallback = buildGravatarUrl(email);
        if (resolvedUrl == null && gravatarFallback == null) {
            return;
        }
        if (resolvedUrl == null) {
            resolvedUrl = gravatarFallback;
            gravatarFallback = null; // primary is already Gravatar, no further fallback needed
        }

        int requestId = sNextRequestId++;
        request = new Request();
        request.id = userId;
        request.url = resolvedUrl;
        request.email = (email != null && !email.trim().isEmpty()) ? email.trim() : null;
        request.fallbackUrl = gravatarFallback;
        request.views = new ArrayList<>();
        request.views.add(view);
        sRequests.put(requestId, request);

        sHandler.removeMessages(MSG_DESTROY);
        if (sWorkerThread == null) {
            sWorkerThread = new HandlerThread("GravatarLoader");
            sWorkerThread.start();
            sWorkerHandler = new WorkerHandler(sWorkerThread.getLooper());
        }
        Message msg = sWorkerHandler.obtainMessage(MSG_LOAD, requestId, 0, request.url);
        msg.sendToTarget();
    }

    private static void initialize(Context context) {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        // Use 10% of the available memory or 1MB for the cache, whatever is larger
        final int limit = Math.max(maxMemory / 10, 1024);

        sCache = new LruCache<Long, Bitmap>(limit) {
            @Override
            protected void entryRemoved(boolean evicted, Long key, Bitmap oldValue, Bitmap newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);
                oldValue.recycle();
            }

            @Override
            protected int sizeOf(Long key, Bitmap value) {
                final long sizeInBytes = value.getAllocationByteCount();
                return (int) (sizeInBytes / 1024);
            }
        };

        Resources res = context.getResources();
        sMaxImageSizePx = Math.round(res.getDisplayMetrics().density * MAX_CACHED_IMAGE_SIZE);
    }

    /**
     * Fetches the avatar URL for a project, walking up the namespace hierarchy when no avatar
     * is set at the project level:
     *   project avatar → parent group/namespace avatar → grandparent group → … → root namespace.
     * Returns null only when no level has an avatar (caller shows initials).
     * The namespace object is embedded in the project response so the first two levels cost
     * only one API call; each subsequent ancestor level costs one /groups/{id} call (max 5).
     */
    private static String fetchProjectAvatarUrl(long projectId) throws IOException {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        OkHttpClient client = ServiceFactory.getImageHttpClient();

        String projectJson = fetchJsonString(client, app.getApiBaseUrl() + "projects/" + projectId);
        if (projectJson == null) return null;

        // Level 1: project's own avatar
        String avatarUrl = parseJsonString(projectJson, "avatar_url");
        if (avatarUrl != null) return avatarUrl;

        // Level 2: immediate parent namespace (embedded — no extra API call)
        String nsJson = parseJsonObject(projectJson, "namespace");
        if (nsJson == null) return null;

        avatarUrl = parseJsonString(nsJson, "avatar_url");
        if (avatarUrl != null) return avatarUrl;

        // User namespaces are always root — nothing further to walk.
        if (!"group".equals(parseJsonString(nsJson, "kind"))) return null;

        // Levels 3+: walk up ancestor groups via parent_id (bounded to 5 levels)
        long parentId = parseJsonLong(nsJson, "parent_id");
        for (int depth = 0; depth < 5 && parentId > 0; depth++) {
            try {
                String groupJson = fetchJsonString(client,
                        app.getApiBaseUrl() + "groups/" + parentId);
                if (groupJson == null) break;
                avatarUrl = parseJsonString(groupJson, "avatar_url");
                if (avatarUrl != null) return avatarUrl;
                parentId = parseJsonLong(groupJson, "parent_id");
            } catch (IOException e) {
                Log.d(TAG, "Group avatar walk-up stopped at parent_id=" + parentId);
                break;
            }
        }
        return null;
    }

    private static String fetchJsonString(OkHttpClient client, String url) throws IOException {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
        try (okhttp3.Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            return resp.body().string();
        }
    }

    /** Returns the unescaped value of {@code "key":"VALUE"}, or null if absent/null/empty. */
    private static String parseJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        if (end <= start) return null;
        String v = json.substring(start, end).replace("\\/", "/").replace("\\u0026", "&");
        return (v.isEmpty() || v.equals("null")) ? null : v;
    }

    /** Returns the long value of {@code "key":N}, or 0 if absent or JSON null. */
    private static long parseJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int start = idx + search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length() || json.charAt(start) == 'n') return 0; // null
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Returns the raw JSON object string for {@code "key":{...}}, or null if absent. */
    private static String parseJsonObject(String json, String key) {
        String search = "\"" + key + "\":{";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length() - 1; // position of opening {
        int depth = 1;
        int i = start + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        return depth == 0 ? json.substring(start, i) : null;
    }

    /**
     * Calls GET /api/v4/users?search=EMAIL&per_page=1.
     * Returns the first matching user's avatar_url — this is the actual uploaded profile picture,
     * unlike /api/v4/avatar?email= which ignores uploaded avatars on some instances and returns
     * a Gravatar URL regardless.
     */
    private static String fetchUserAvatarByEmail(String email) throws IOException {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        String apiUrl = app.getApiBaseUrl() + "users?search="
                + android.net.Uri.encode(email) + "&per_page=1";

        OkHttpClient client = ServiceFactory.getImageHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder().url(apiUrl).build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Users API HTTP " + response.code());
            okhttp3.ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty Users API body");
            String json = body.string();
            if (json.trim().startsWith("[]") || !json.contains("avatar_url")) return null;

            // Parse id
            long userId = 0;
            int idIdx = json.indexOf("\"id\":");
            if (idIdx >= 0) {
                int idStart = idIdx + 5;
                int idEnd = json.indexOf(",", idStart);
                if (idEnd > idStart) {
                    try { userId = Long.parseLong(json.substring(idStart, idEnd).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
            // Parse username
            String username = null;
            int unIdx = json.indexOf("\"username\":\"");
            if (unIdx >= 0) {
                int unStart = unIdx + 12;
                int unEnd = json.indexOf("\"", unStart);
                if (unEnd > unStart) username = json.substring(unStart, unEnd);
            }
            // Parse avatar_url
            int avIdx = json.indexOf("\"avatar_url\":\"");
            if (avIdx < 0) return null;
            int avStart = avIdx + 14;
            int avEnd = json.indexOf("\"", avStart);
            if (avEnd <= avStart) return null;
            String avatarUrl = json.substring(avStart, avEnd)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            // Cache the resolved user so avatar clicks can open the correct profile.
            if (userId > 0 && username != null) {
                GitLabUser resolved = new GitLabUser();
                resolved.id = userId;
                resolved.username = username;
                resolved.avatarUrl = avatarUrl;
                sEmailUserCache.put(email.toLowerCase(java.util.Locale.ROOT).trim(), resolved);
            }

            return avatarUrl.contains("gravatar.com") ? null : avatarUrl;
        }
    }

    /**
     * Calls GET /api/v4/avatar?email=EMAIL&size=N (authenticated via sImageHttpClient interceptor).
     * Returns the avatar_url from the JSON response, which is always an accessible URL
     * (Gravatar or GitLab-CDN), bypassing /uploads/ auth restrictions.
     */
    private static String fetchAvatarUrlFromApi(String email) throws IOException {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        String apiUrl = app.getApiBaseUrl() + "avatar?email="
                + android.net.Uri.encode(email) + "&size=" + sMaxImageSizePx;

        OkHttpClient client = ServiceFactory.getImageHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder().url(apiUrl).build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Avatar API HTTP " + response.code());
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty Avatar API body");
            String json = body.string();
            // Parse "avatar_url":"VALUE" from JSON without adding a dependency
            int keyIdx = json.indexOf("\"avatar_url\":\"");
            if (keyIdx < 0) return null;
            int start = keyIdx + 14;
            int end = json.indexOf("\"", start);
            if (end <= start) return null;
            // Unescape JSON unicode escapes in the URL (e.g. & → &)
            return json.substring(start, end)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");
        }
    }

    private static String buildGravatarUrl(String email) {
        if (email == null || email.trim().isEmpty()) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(email.trim().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            // d=404 means return 404 (not a placeholder) if no Gravatar exists for this email.
            return "https://www.gravatar.com/avatar/" + hex + "?s=" + sMaxImageSizePx + "&d=404";
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String makeUrl(String url, long userId) {
        if (url == null) {
            return null;
        }
        // If the URL is relative, prepend the instance base URL.
        if (url.startsWith("/")) {
            url = com.gl4a.Gl4Application.get().getInstanceUrl() + url;
        }
        Uri.Builder builder = Uri.parse(url).buildUpon()
                .appendQueryParameter("s", String.valueOf(sMaxImageSizePx));
        // Auth for instance avatars is handled by the sImageHttpClient interceptor in
        // ServiceFactory which adds PRIVATE-TOKEN header for all requests to the instance host.
        // We do NOT add ?private_token to the URL — tokens in URLs appear in server logs.
        // Restricted instances (header auth also rejected) are handled by Gravatar fallback.
        return builder.toString();
    }

    private static void applyAvatarToView(ViewDelegate view, Bitmap avatar) {
        Resources res = view.getContext().getResources();
        RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(res, avatar);
        d.setCornerRadius(Math.max(avatar.getWidth() / 2, avatar.getHeight() / 2));
        d.setAntiAlias(true);

        Drawable old = view.getDrawable();
        if (old instanceof DefaultAvatarDrawable) {
            TransitionDrawable transition = new TransitionDrawable(new Drawable[] { old, d });
            transition.setCrossFadeEnabled(true);
            transition.startTransition(res.getInteger(android.R.integer.config_shortAnimTime));
            view.setDrawable(transition);
        } else {
            view.setDrawable(d);
        }
    }

    private static Request getRequestForId(long id) {
        int count = sRequests.size();
        for (int i = 0; i < count; i++) {
            Request request = sRequests.valueAt(i);
            if (request.id == id) {
                return request;
            }
        }
        return null;
    }

    private static void removeOldRequest(ViewDelegate view) {
        int count = sRequests.size();
        for (int i = 0; i < count; i++) {
            Request request = sRequests.valueAt(i);
            if (request.views.remove(view)) {
                if (request.views.isEmpty()) {
                    if (sWorkerHandler != null) {
                        sWorkerHandler.removeMessages(MSG_LOAD, request.url);
                    }
                    sRequests.removeAt(i);
                }
                return;
            }
        }
    }

    private static Bitmap fetchBitmap(String url) throws IOException {
        // gitlab.com and some self-hosted instances return relative avatar URLs (e.g. /uploads/...).
        // OkHttp requires an absolute URL; prepend the instance base URL when the path is relative.
        if (url != null && url.startsWith("/")) {
            String base = com.gl4a.Gl4Application.get().getInstanceUrl();
            if (base != null) url = base + url;
        }
        OkHttpClient client = ServiceFactory.getImageHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .build();

        byte[] data;

        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            okhttp3.ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body for " + url);
            }
            data = body.bytes();
        }

        if (data.length == 0) {
            throw new IOException("Zero-length image data for " + url);
        }

        // Avatar images are small (60–180px requested via ?s=). Simple decode is sufficient —
        // the previous two-pass inSampleSize approach returned null when image dimensions were
        // smaller than sMaxImageSizePx (ratio = 0 in integer division).
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) {
            throw new IOException("BitmapFactory could not decode image from " + url);
        }
        bitmap.setDensity(0);
        return bitmap;
    }

    private static void shutdownWorker() {
        if (sWorkerThread != null) {
            sWorkerThread.getLooper().quit();
            sWorkerHandler = null;
            sWorkerThread = null;
        }
    }

    private static class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOAD:
                    String url = (String) msg.obj;
                    int requestId = msg.arg1;
                    Request req = sRequests.get(requestId);
                    Bitmap bitmap = null;
                    if (req != null && req.apiFirst && req.projectId > 0) {
                        // Project avatar path: fetch via GET /projects/{id}.
                        try {
                            String projectAvatarUrl = fetchProjectAvatarUrl(req.projectId);
                            if (projectAvatarUrl != null) bitmap = fetchBitmap(projectAvatarUrl);
                        } catch (Exception e) {
                            Log.d(TAG, "Project avatar fetch failed for id=" + req.projectId);
                        }
                    } else if (req != null && req.apiFirst && req.email != null) {
                        // Email-only path: user search first (returns actual uploaded avatar),
                        // then Gravatar (req.url) as fallback.
                        try {
                            String userAvatarUrl = fetchUserAvatarByEmail(req.email);
                            if (userAvatarUrl != null) bitmap = fetchBitmap(userAvatarUrl);
                        } catch (Exception e) {
                            Log.d(TAG, "User search failed for " + req.email + ", trying Gravatar");
                        }
                        if (bitmap == null && req.url != null) {
                            try {
                                bitmap = fetchBitmap(req.url);
                            } catch (Exception e2) {
                                Log.d(TAG, "Gravatar fallback also failed for " + req.email);
                            }
                        }
                    } else {
                        try {
                            bitmap = fetchBitmap(url);
                        } catch (Exception e) {
                            // Tier 2: GitLab Avatar API — GET /api/v4/avatar?email=EMAIL&size=N
                            // Works on instances where /uploads/ rejects token auth.
                            if (req != null && req.email != null) {
                                try {
                                    String apiUrl = fetchAvatarUrlFromApi(req.email);
                                    if (apiUrl != null && !apiUrl.equals(url)) {
                                        bitmap = fetchBitmap(apiUrl);
                                    }
                                } catch (Exception e2) {
                                    Log.d(TAG, "Avatar API fallback failed for " + req.email);
                                }
                            }
                            // Tier 3: Gravatar by email hash
                            if (bitmap == null && req != null && req.fallbackUrl != null) {
                                try {
                                    bitmap = fetchBitmap(req.fallbackUrl);
                                } catch (Exception e3) {
                                    Log.d(TAG, "Gravatar fallback also failed");
                                }
                            }
                            if (bitmap == null) {
                                Log.e(TAG, "All avatar sources failed for " + url, e);
                            }
                        }
                    }
                    sHandler.obtainMessage(MSG_LOADED, requestId, 0, bitmap).sendToTarget();
                    break;
            }
        }
    }

    public static class DefaultAvatarDrawable extends Drawable {
        private static final @ColorInt int[] COLOR_PALETTE = {
            0xffdb4437, 0xffe91e63, 0xff9c27b0, 0xff673ab7,
            0xff3f51b5, 0xff4285f4, 0xff039be5, 0xff0097a7,
            0xff009688, 0xff0f9d58, 0xff689f38, 0xffef6c00,
            0xffff5722, 0xff757575
        };
        private static final float LETTER_TO_TILE_RATIO = 0.67f;

        private final Paint mPaint;
        private final @ColorInt int mColor;
        private final char[] mLetter = new char[1];
        private final UserNameState mState;
        private static final Rect sRect = new Rect();

        public DefaultAvatarDrawable(String userName, Object identifier) {
            mState = new UserNameState(userName, identifier);

            mPaint = new Paint();
            mPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setAntiAlias(true);

            final int colorIndex;
            if (TextUtils.isEmpty(userName)) {
                mLetter[0] = '?';
                if (mState.mIdentifier != null) {
                    colorIndex = Math.abs(mState.mIdentifier.hashCode()) % COLOR_PALETTE.length;
                } else {
                    colorIndex = (int) (Math.random() * COLOR_PALETTE.length);
                }
            } else {
                mLetter[0] = Character.toUpperCase(userName.charAt(0));
                colorIndex = Math.abs(userName.hashCode()) % COLOR_PALETTE.length;
            }

            mColor = COLOR_PALETTE[colorIndex];
        }

        @Nullable
        @Override
        public ConstantState getConstantState() {
            return mState;
        }

        @Override
        public void draw(@NonNull final Canvas canvas) {
            final Rect bounds = getBounds();
            if (!isVisible() || bounds.isEmpty()) {
                return;
            }

            mPaint.setColor(mColor);

            final int minDimension = Math.min(bounds.width(), bounds.height());
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), minDimension / 2, mPaint);

            mPaint.setTextSize(LETTER_TO_TILE_RATIO * minDimension);
            mPaint.getTextBounds(mLetter, 0, 1, sRect);
            mPaint.setColor(Color.WHITE);

            canvas.drawText(mLetter, 0, 1, bounds.centerX(),
                    bounds.centerY() - sRect.exactCenterY(),
                    mPaint);
        }

        @Override
        public void setAlpha(final int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(final ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }

        private static class UserNameState extends ConstantState {
            private final String mUserName;
            private final Object mIdentifier;

            public UserNameState(String userName, Object identifier) {
                mUserName = userName;
                mIdentifier = identifier;
            }

            @NonNull
            @Override
            public Drawable newDrawable() {
                return new DefaultAvatarDrawable(mUserName, mIdentifier);
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        }
    }

    private interface ViewDelegate {
        Context getContext();
        Drawable getDrawable();
        void setDrawable(Drawable d);
    }

    private static class ImageViewDelegate implements ViewDelegate {
        private final ImageView mView;
        public ImageViewDelegate(ImageView view) {
            mView = view;
        }
        @Override
        public Context getContext() {
            return mView.getContext();
        }
        @Override
        public Drawable getDrawable() {
            return mView.getDrawable();
        }
        @Override
        public void setDrawable(Drawable d) {
            mView.setImageDrawable(d);
        }
        @Override
        public boolean equals(Object obj) {
            return obj instanceof ImageViewDelegate && ((ImageViewDelegate) obj).mView == mView;
        }
    }

    private static class MenuItemDelegate implements ViewDelegate {
        private final Context mContext;
        private final MenuItem mItem;
        public MenuItemDelegate(Context context, MenuItem item) {
            mContext = context;
            mItem = item;
        }
        @Override
        public Context getContext() {
            return mContext;
        }
        @Override
        public Drawable getDrawable() {
            return mItem.getIcon();
        }
        @Override
        public void setDrawable(Drawable d) {
            mItem.setIcon(d);
        }
        @Override
        public boolean equals(Object obj) {
            return obj instanceof MenuItemDelegate && ((MenuItemDelegate) obj).mItem == mItem;
        }
    }
}