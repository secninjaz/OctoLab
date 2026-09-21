package com.gl4a.utils;
import com.gl4a.gitlab.model.GitLabUser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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

    // Hairline gap (dp) kept between a project/group logo tile and its surrounding
    // rounded-square frame (avatar_frame.xml) — shared by both the real-logo path
    // (fitLogoToSquare) and the initials-tile placeholder (DefaultAvatarDrawable) so
    // a loaded logo and its own placeholder look consistent, and neither renders
    // perfectly flush against the frame (which reads as the two shapes merging).
    private static final float LOGO_INSET_DP = 1f;

    private static LruCache<Long, Bitmap> sCache;
    private static int sNextRequestId = 1;

    private static class Request {
        long id;
        String url;
        String email;        // used to call /api/v4/avatar?email= if primary URL fails
        String fallbackUrl;  // Gravatar URL, tried if Avatar API also fails
        boolean apiFirst;    // true → try GitLab Avatar API before url (for email-only lookups)
        long projectId;      // > 0 → fetch avatar via GET /projects/{id} (project avatar path)
        // true → use rounded-rectangle clip (20% radius) instead of full circle.
        // Square org/group logos have content at the corners; full circle clips them.
        boolean isLogo;
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

    private static final int MSG_LOAD       = 1;
    private static final int MSG_LOADED     = 2;
    private static final int MSG_DESTROY    = 3;
    private static final int MSG_DISK_LOADED = 4;

    private static HandlerThread sWorkerThread = null;
    private static Handler sWorkerHandler = null;

    private static final Handler sHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISK_LOADED:
                    // Disk bitmap arrived — show immediately as placeholder, no crossfade.
                    applyDiskPlaceholder(msg.arg1, (Bitmap) msg.obj);
                    break;
                case MSG_LOADED:
                    // arg2 == 1 means the network bitmap differs from disk — crossfade allowed.
                    processResult(msg.arg1, (Bitmap) msg.obj, msg.arg2 == 1);
                    if (sRequests.size() == 0) {
                        sendEmptyMessageDelayed(MSG_DESTROY, 3000);
                    }
                    break;
                case MSG_DESTROY:
                    shutdownWorker();
                    break;
            }
        }

        private void applyDiskPlaceholder(int requestId, Bitmap diskBitmap) {
            Request request = sRequests.get(requestId);
            if (request == null || diskBitmap == null) return;
            // Seed LruCache so same-session requests for this id get an instant hit.
            synchronized (AvatarHandler.class) {
                if (sCache == null) return;
                if (sCache.get(request.id) == null) sCache.put(request.id, diskBitmap);
            }
            // Apply the same logo treatment as processResult so disk-cache hits render
            // identically to network-loaded bitmaps (fixes first-load circle crop).
            Bitmap toDisplay = request.isLogo ? fitLogoToSquare(diskBitmap) : diskBitmap;
            float radius = request.isLogo
                    ? Math.max(toDisplay.getWidth(), toDisplay.getHeight()) * 0.20f
                    : Math.max(toDisplay.getWidth() / 2, toDisplay.getHeight() / 2);
            Resources res = request.views.get(0).getContext().getResources();
            RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(res, toDisplay);
            d.setCornerRadius(radius);
            d.setAntiAlias(true);
            for (ViewDelegate view : request.views) {
                view.setDrawable(d);
            }
        }

        private void processResult(int requestId, Bitmap bitmap, boolean changed) {
            final Request request = sRequests.get(requestId);
            if (request != null) {
                if (bitmap != null) {
                    synchronized (AvatarHandler.class) {
                        sCache.put(request.id, bitmap);
                    }
                    if (changed) {
                        for (ViewDelegate view : request.views) {
                            if (request.isLogo) {
                                Bitmap logo = fitLogoToSquare(bitmap);
                                applyAvatarToView(view, logo, true,
                                        Math.max(logo.getWidth(), logo.getHeight()) * 0.20f);
                            } else {
                                applyAvatarToView(view, bitmap);
                            }
                        }
                    }
                } else {
                    // Network failed — apply cached bitmap (LruCache or disk) so views never
                    // stay on initials when a cached avatar exists from a previous session.
                    Bitmap fallback;
                    synchronized (AvatarHandler.class) {
                        fallback = (sCache != null) ? sCache.get(request.id) : null;
                    }
                    if (fallback == null) {
                        Context ctx = com.gl4a.Gl4Application.get();
                        fallback = (ctx != null) ? loadAvatarFromDisk(ctx, request.id) : null;
                        if (fallback != null) {
                            synchronized (AvatarHandler.class) {
                                if (sCache != null && sCache.get(request.id) == null) {
                                    sCache.put(request.id, fallback);
                                }
                            }
                        }
                    }
                    if (fallback != null) {
                        applyDiskPlaceholder(requestId, fallback);
                    }
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
        // Persist the avatar URL so it is available when this account is shown as an
        // inactive MenuItem in the drawer (which has no GitLabUser object available).
        String avatarUrl = user.avatarUrl();
        if (user.login() != null && avatarUrl != null) {
            com.gl4a.Gl4Application.get().updateStoredAvatarUrl(user.login(), avatarUrl);
        }
        // Pass email so we can fall back to Gravatar if the instance avatar fails.
        assignAvatarInternal(new ImageViewDelegate(view), user.login(), user.id(),
                avatarUrl, user.email);
    }

    public static void assignAvatar(ImageView view, String userName, long userId, String url) {
        assignAvatarInternal(new ImageViewDelegate(view), userName, userId, url, null, false);
    }

    /**
     * Like {@link #assignAvatar} but uses a rounded-rectangle clip (20% corner radius) instead
     * of a full circle. Use for project/group logos whose content extends to the image corners.
     */
    public static void assignAvatarLogo(ImageView view, String name, long id, String url) {
        assignAvatarInternal(new ImageViewDelegate(view), name, id, url, null, true);
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
            applyAvatarToView(delegate, cached, false);
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
            view.setImageDrawable(new DefaultAvatarDrawable(projectName, null, true));
            return;
        }
        // Use a large negative offset so project IDs don't collide with user IDs in the cache.
        long cacheId = Long.MIN_VALUE / 2 + projectId;

        ImageViewDelegate delegate = new ImageViewDelegate(view);
        removeOldRequest(delegate);

        Bitmap cached = loadBitmapFromCache(view.getContext(), cacheId);
        if (cached != null) {
            // Project avatar — rounded-square clip (20% radius), matching RepositoryAdapter.
            Bitmap logo = fitLogoToSquare(cached);
            applyAvatarToView(delegate, logo, false,
                    Math.max(logo.getWidth(), logo.getHeight()) * 0.20f);
            return;
        }

        view.setImageDrawable(new DefaultAvatarDrawable(projectName, projectId, true));

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
        request.isLogo = true; // project avatar — rounded-square, not a full circle
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

    /**
     * Renders the same rounded-square colored initials tile used in-app as a project's
     * placeholder logo, as a plain Bitmap — for contexts like notifications that need a
     * raw Bitmap (NotificationCompat.Builder#setLargeIcon) rather than a View to apply
     * a Drawable to. Most projects have no uploaded avatar, so this is the common case,
     * not just a network-failure fallback.
     */
    public static Bitmap renderProjectPlaceholder(String projectName, long projectId, int sizePx) {
        DefaultAvatarDrawable drawable = new DefaultAvatarDrawable(projectName,
                projectId > 0 ? projectId : null, true);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    /**
     * Synchronously resolves a project's avatar for contexts outside the async View-based
     * pipeline (e.g. notifications) — reuses the exact same URL resolution (including the
     * parent-namespace fallback walk) and fetch logic as the in-app path
     * (assignAvatarForProject), so a project showing a real logo or an inherited group logo
     * in-app shows the same thing in a notification, not just its own possibly-unset avatar.
     * Falls back to the same colored initials-tile placeholder when nothing is set anywhere
     * in the hierarchy. Performs blocking network I/O — call from a background thread only.
     */
    public static Bitmap loadProjectAvatarSynchronously(String projectName, long projectId,
            int placeholderSizePx) {
        if (projectId > 0) {
            try {
                String url = fetchProjectAvatarUrl(projectId);
                if (url != null) {
                    Bitmap bitmap = fetchBitmap(url);
                    if (bitmap != null) {
                        return fitLogoToSquare(bitmap);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "loadProjectAvatarSynchronously failed for id=" + projectId, e);
            }
        }
        return renderProjectPlaceholder(projectName, projectId, placeholderSizePx);
    }
    public static void assignAvatar(Context context, MenuItem item,
            String userName, long userId) {
        // Look up the stored avatar URL so inactive accounts can also do a network fetch
        // and benefit from the disk cache across sessions.
        String avatarUrl = com.gl4a.Gl4Application.get().getAvatarUrlForLogin(userName);
        assignAvatarInternal(new MenuItemDelegate(context, item), userName, userId, avatarUrl, null);
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
                saveToDisk(user.id(), bitmap);
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
        assignAvatarInternal(view, userName, userId, url, email, false);
    }

    private static void assignAvatarInternal(ViewDelegate view,
            String userName, long userId, String url, String email, boolean isLogo) {
        removeOldRequest(view);

        Bitmap bitmap = loadBitmapFromCache(view.getContext(), userId);
        if (bitmap != null) {
            if (isLogo) {
                Bitmap logo = fitLogoToSquare(bitmap);
                applyAvatarToView(view, logo, false,
                        Math.max(logo.getWidth(), logo.getHeight()) * 0.20f);
            } else {
                applyAvatarToView(view, bitmap, false);
            }
            return;
        }

        view.setDrawable(new DefaultAvatarDrawable(userName, userId, isLogo));
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
        request.isLogo = isLogo;
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
            protected int sizeOf(Long key, Bitmap value) {
                final long sizeInBytes = value.getAllocationByteCount();
                return (int) (sizeInBytes / 1024);
            }
            // Do NOT override entryRemoved to call recycle(). On API 11+ with hardware
            // acceleration, RoundedBitmapDrawables attached to views can outlive the
            // LruCache entry; recycling the bitmap while it is still drawn causes
            // "BitmapShader's bitmap has been recycled" crashes. The GC handles cleanup.
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

        try {
            org.json.JSONObject project = new org.json.JSONObject(projectJson);

            // Level 1: project's own avatar
            String avatarUrl = jsonAvatarUrl(project);
            if (avatarUrl != null) return avatarUrl;

            // Level 2: immediate parent namespace (embedded — no extra API call)
            org.json.JSONObject ns = project.optJSONObject("namespace");
            if (ns == null) return null;

            avatarUrl = jsonAvatarUrl(ns);
            if (avatarUrl != null) return avatarUrl;

            // User namespaces are always root — nothing further to walk.
            if (!"group".equals(ns.optString("kind", ""))) return null;

            // Levels 3+: walk up ancestor groups via parent_id (bounded to 5 levels)
            long parentId = ns.optLong("parent_id", 0);
            for (int depth = 0; depth < 5 && parentId > 0; depth++) {
                String groupJson = fetchJsonString(client,
                        app.getApiBaseUrl() + "groups/" + parentId);
                if (groupJson == null) break;
                org.json.JSONObject group = new org.json.JSONObject(groupJson);
                avatarUrl = jsonAvatarUrl(group);
                if (avatarUrl != null) return avatarUrl;
                parentId = group.optLong("parent_id", 0);
            }
        } catch (org.json.JSONException e) {
            Log.d(TAG, "JSON parse error in fetchProjectAvatarUrl for id=" + projectId, e);
        }
        return null;
    }

    /** Returns avatar_url from a JSONObject, or null if absent, JSON null, or empty string. */
    @Nullable
    private static String jsonAvatarUrl(org.json.JSONObject obj) {
        if (obj.isNull("avatar_url")) return null;
        String v = obj.optString("avatar_url", "");
        return v.isEmpty() ? null : v;
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
        applyAvatarToView(view, avatar, true);
    }

    /**
     * @param crossfade if false, always sets directly without crossfade transition.
     *                  Use false for LruCache hits to prevent flicker when a RecyclerView
     *                  ViewHolder was recycled and shows a different item's placeholder.
     */
    private static void applyAvatarToView(ViewDelegate view, Bitmap avatar, boolean crossfade) {
        // Full circle for user/person avatars (standard profile photo style).
        applyAvatarToView(view, avatar, crossfade, Math.max(avatar.getWidth() / 2f, avatar.getHeight() / 2f));
    }

    /**
     * Fits a bitmap into a square canvas by centering it with transparent padding.
     * Prevents RoundedBitmapDrawable's CENTER_CROP from cutting portrait-oriented logos.
     * Only pads by a hairline (LOGO_INSET_DP) beyond what's needed to become square — a
     * previous fixed 20% inset here made every logo look shrunk inside its frame (#158).
     */
    private static Bitmap fitLogoToSquare(Bitmap src) {
        // Same hairline inset DefaultAvatarDrawable's placeholder uses (LOGO_INSET_DP), so a
        // loaded logo and its own placeholder look consistent. Applied on every source, square
        // or not — a square source previously returned unchanged (flush to the frame); a wide
        // or tall source is only padded on its shorter axis to become square, which still
        // leaves its longer axis flush unless the canvas itself is inset too.
        int inset = Math.round(com.gl4a.Gl4Application.get().getResources()
                .getDisplayMetrics().density * LOGO_INSET_DP);
        int size = Math.max(src.getWidth(), src.getHeight()) + inset * 2;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        int left = (size - src.getWidth()) / 2;
        int top = (size - src.getHeight()) / 2;
        canvas.drawBitmap(src, left, top, null);
        return result;
    }

    private static void applyAvatarToView(ViewDelegate view, Bitmap avatar, boolean crossfade,
            float cornerRadius) {
        Resources res = view.getContext().getResources();
        RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(res, avatar);
        d.setCornerRadius(cornerRadius);
        d.setAntiAlias(true);

        if (crossfade) {
            Drawable old = view.getDrawable();
            if (old instanceof DefaultAvatarDrawable) {
                TransitionDrawable transition = new TransitionDrawable(new Drawable[] { old, d });
                transition.setCrossFadeEnabled(true);
                transition.startTransition(res.getInteger(android.R.integer.config_shortAnimTime));
                view.setDrawable(transition);
                return;
            }
        }
        view.setDrawable(d);
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

    // ── Disk avatar cache ────────────────────────────────────────────────────
    // Bitmaps are persisted to filesDir/avatars/{id}.png as a stale-while-revalidate
    // placeholder. On app restart, assignAvatarInternal() shows the disk bitmap
    // immediately (no initials flash) but still triggers a background network fetch
    // so the avatar is always refreshed from the server each session.

    private static File avatarFile(Context ctx, long id) {
        File dir = new File(ctx.getFilesDir(), "avatars");
        dir.mkdirs();
        return new File(dir, id + ".png");
    }

    private static void saveToDisk(long id, Bitmap bitmap) {
        Context ctx = com.gl4a.Gl4Application.get();
        if (ctx == null || bitmap == null) return;
        try (FileOutputStream fos = new FileOutputStream(avatarFile(ctx, id))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
        } catch (Exception e) {
            Log.d(TAG, "Failed to save avatar to disk for id=" + id);
        }
    }

    /**
     * Reads the persisted avatar for userId from disk, or null if none.
     * Does NOT populate the LruCache — the caller decides what to do with it.
     */
    @Nullable
    static Bitmap loadAvatarFromDisk(Context context, long id) {
        File f = new File(new File(context.getFilesDir(), "avatars"), id + ".png");
        if (!f.exists()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    /**
     * No-op stub kept for call-site compatibility. Disk is read on demand in
     * assignAvatarInternal(); pre-loading into the LruCache would suppress the
     * background network refresh that keeps avatars up-to-date.
     */
    public static void prewarmFromDisk(Context context) {
        // Intentionally empty — see loadAvatarFromDisk() / assignAvatarInternal().
    }
    // ── end disk avatar cache ────────────────────────────────────────────────

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
                    // Read disk before network so we can send a placeholder immediately and
                    // compare after the fetch — both on the background thread.
                    Bitmap diskBitmap = null;
                    if (req != null) {
                        Context ctx = com.gl4a.Gl4Application.get();
                        if (ctx != null) {
                            diskBitmap = loadAvatarFromDisk(ctx, req.id);
                            if (diskBitmap != null) {
                                sHandler.obtainMessage(MSG_DISK_LOADED, requestId, 0, diskBitmap)
                                        .sendToTarget();
                            }
                        }
                    }
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
                    boolean changed = bitmap != null
                            && (diskBitmap == null || !bitmap.sameAs(diskBitmap));
                    if (changed && req != null) saveToDisk(req.id, bitmap);
                    sHandler.obtainMessage(MSG_LOADED, requestId, changed ? 1 : 0, bitmap)
                            .sendToTarget();
                    break;
            }
        }
    }

    public static class DefaultAvatarDrawable extends Drawable {
        // GitLab's own identicon palette (design.gitlab.com "Avatar" component), reproduced
        // from gitlab-ui's design tokens (--gl-avatar-fallback-background/text-color-*:
        // red, purple, blue, green, orange, neutral). Index 2 duplicates index 1 (purple) —
        // GitLab did this on purpose when retiring indigo for contrast reasons, rather than
        // shrinking the array, since that would reshuffle which color every existing
        // identicon gets (same reasoning applies here, so the duplicate is kept as-is).
        // GitLab's tokens are semi-transparent overlays meant to blend with the page
        // background; these are pre-composited against this app's actual theme_surface
        // colors (#FFFFF5 light / #121212 dark) into opaque equivalents, then WCAG-AA
        // verified (>=4.5:1) against the paired text token for each theme.
        private static final @ColorInt int[] BG_PALETTE_LIGHT = {
            0xfffeede3, 0xfff3eff4, 0xfff3eff4, 0xffe8f2f4, 0xffe5f5e3, 0xfffaefd6, 0xfff0f0e9
        };
        private static final @ColorInt int[] BG_PALETTE_DARK = {
            0xff4a3936, 0xff3e3a48, 0xff3e3a48, 0xff333d47, 0xff304036, 0xff453b29, 0xff3b3b3c
        };
        private static final @ColorInt int[] TEXT_PALETTE_LIGHT = {
            0xff812713, 0xff493c83, 0xff493c83, 0xff284779, 0xff225131, 0xff693c14, 0xff3a383f
        };
        private static final @ColorInt int[] TEXT_PALETTE_DARK = {
            0xfffcb5aa, 0xffcbbbf2, 0xffcbbbf2, 0xff9dc7f1, 0xff91d4a8, 0xffe9be74, 0xffbfbfc3
        };
        private static final float LETTER_TO_TILE_RATIO = 0.67f;

        private final Paint mPaint;
        private final @ColorInt int mColor;
        private final @ColorInt int mTextColor;
        private final char[] mLetter = new char[1];
        private final boolean mIsLogo;
        private final float mLogoInset;
        private final UserNameState mState;
        private static final Rect sRect = new Rect();
        private static final android.graphics.RectF sRectF = new android.graphics.RectF();

        public DefaultAvatarDrawable(String userName, Object identifier) {
            this(userName, identifier, false);
        }

        /**
         * @param isLogo true draws a rounded-square placeholder (20% radius) matching the
         *                shape project/group logos use once loaded, instead of a full circle.
         */
        public DefaultAvatarDrawable(String userName, Object identifier, boolean isLogo) {
            mIsLogo = isLogo;
            mState = new UserNameState(userName, identifier, isLogo);

            mPaint = new Paint();
            mPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setAntiAlias(true);

            Resources resources = com.gl4a.Gl4Application.get().getResources();
            // Hairline inset so the tile doesn't render perfectly flush against the row's
            // rounded-square frame background (avatar_frame.xml) — without it the two
            // rounded-rects visually merge into one hard edge with no framing at all.
            mLogoInset = resources.getDisplayMetrics().density * LOGO_INSET_DP;
            boolean darkTheme = resources.getBoolean(com.gl4a.R.bool.is_dark_theme);
            @ColorInt int[] bgPalette = darkTheme ? BG_PALETTE_DARK : BG_PALETTE_LIGHT;
            @ColorInt int[] textPalette = darkTheme ? TEXT_PALETTE_DARK : TEXT_PALETTE_LIGHT;

            final int colorIndex;
            if (TextUtils.isEmpty(userName)) {
                mLetter[0] = '?';
                if (mState.mIdentifier != null) {
                    colorIndex = Math.abs(mState.mIdentifier.hashCode()) % bgPalette.length;
                } else {
                    colorIndex = (int) (Math.random() * bgPalette.length);
                }
            } else {
                mLetter[0] = Character.toUpperCase(userName.charAt(0));
                colorIndex = Math.abs(userName.hashCode()) % bgPalette.length;
            }

            mColor = bgPalette[colorIndex];
            mTextColor = textPalette[colorIndex];
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
            if (mIsLogo) {
                sRectF.set(bounds.left + mLogoInset, bounds.top + mLogoInset,
                        bounds.right - mLogoInset, bounds.bottom - mLogoInset);
                float radius = minDimension * 0.20f;
                canvas.drawRoundRect(sRectF, radius, radius, mPaint);
            } else {
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), minDimension / 2, mPaint);
            }

            mPaint.setTextSize(LETTER_TO_TILE_RATIO * minDimension);
            mPaint.getTextBounds(mLetter, 0, 1, sRect);
            mPaint.setColor(mTextColor);

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
            private final boolean mIsLogo;

            public UserNameState(String userName, Object identifier, boolean isLogo) {
                mUserName = userName;
                mIdentifier = identifier;
                mIsLogo = isLogo;
            }

            @NonNull
            @Override
            public Drawable newDrawable() {
                return new DefaultAvatarDrawable(mUserName, mIdentifier, mIsLogo);
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