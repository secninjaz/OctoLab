package com.gl4a.worker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import android.text.SpannableStringBuilder;
import android.text.style.TextAppearanceSpan;
import android.util.Log;

import com.gl4a.R;
import com.gl4a.ServiceFactory;
import com.gl4a.activities.home.HomeActivity;
import com.gl4a.fragment.SettingsFragment;
import com.gl4a.gitlab.model.GitLabTodo;
import com.gl4a.gitlab.service.GitLabTodoService;
import com.gl4a.utils.ApiHelpers;
import com.gl4a.utils.AvatarHandler;
import com.gl4a.utils.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class NotificationsWorker extends Worker {
    private static final String TAG = "NotificationsWorker";

    private static final String CHANNEL_GITLAB_NOTIFICATIONS = "channel_notifications";
    private static final String GROUP_ID_GITLAB = "gitlab_notifications";
    public static final String WORK_TAG = "job_notifications";

    private static final String KEY_LAST_NOTIFICATION_CHECK = "last_notification_check";
    private static final String KEY_LAST_NOTIFICATION_SEEN = "last_notification_seen";
    private static final String KEY_LAST_SHOWN_PROJECT_IDS = "last_notification_repo_ids";

    private static final Object sPrefsLock = new Object();

    public static void schedule(Context context, int intervalMinutes) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                NotificationsWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build();
        Log.d(TAG, "Scheduling notification fetch to happen every " + intervalMinutes + " min");
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_TAG,
                ExistingPeriodicWorkPolicy.REPLACE, request);
    }

    public static void cancel(Context context) {
        Log.d(TAG, "Canceling notification fetch");
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG);
    }

    /**
     * Returns the most recent last-check timestamp (ms) across all logged-in accounts,
     * or 0 if the worker has never run.
     */
    public static long getLastCheckMillis(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(com.gl4a.fragment.SettingsFragment.PREF_NAME,
                        Context.MODE_PRIVATE);
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        long latest = 0;
        for (String login : app.getAllLogins()) {
            long t = prefs.getLong(KEY_LAST_NOTIFICATION_CHECK + "_" + login, 0);
            if (t > latest) latest = t;
        }
        if (latest == 0) latest = prefs.getLong(KEY_LAST_NOTIFICATION_CHECK, 0);
        return latest;
    }

    /** Enqueues an immediate one-time run of the notification worker. */
    public static void runNow(Context context) {
        androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(
                NotificationsWorker.class)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(context).enqueue(req);
    }

    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(CHANNEL_GITLAB_NOTIFICATIONS,
                context.getString(R.string.channel_notifications_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.channel_notifications_description));

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);
    }

    public static void markNotificationsAsSeen(Context context) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();

        synchronized (sPrefsLock) {
            SharedPreferences prefs = getPrefs(context);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            // Update seen timestamp for every known account so the worker knows
            // the user has viewed the notifications and won't re-alert for them.
            java.util.Set<String> logins = prefs.getStringSet("logins", new java.util.HashSet<>());
            for (String login : logins) {
                editor.putLong(keyLastSeen(login), now);
                editor.remove(keyShownIds(login));
            }
            // Also update legacy global key for any older code paths.
            editor.putLong(KEY_LAST_NOTIFICATION_SEEN, now);
            editor.putStringSet(KEY_LAST_SHOWN_PROJECT_IDS, null);
            editor.apply();
        }
    }

    public static long getLastCheckTimestamp(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (!prefs.getBoolean(SettingsFragment.KEY_NOTIFICATIONS, true)) {
            return 0;
        }
        return prefs.getLong(KEY_LAST_NOTIFICATION_CHECK, 0);
    }

    private static String keyLastCheck(String login) {
        return KEY_LAST_NOTIFICATION_CHECK + "_" + login;
    }

    private static String keyLastSeen(String login) {
        return KEY_LAST_NOTIFICATION_SEEN + "_" + login;
    }

    private static String keyShownIds(String login) {
        return KEY_LAST_SHOWN_PROJECT_IDS + "_" + login;
    }

    // Unique notification ID: combines a stable hash of the account login with the project id
    // so notifications from different accounts on the same project don't collide.
    private static int notificationId(String login, long projectId) {
        return (login.hashCode() & 0x0000FFFF) << 16 | (int) (projectId & 0x0000FFFF);
    }

    public static void handleNotificationDismiss(Context context, int id) {
        SharedPreferences prefs = getPrefs(context);
        String idString = String.valueOf(id);

        synchronized (sPrefsLock) {
            Set<String> lastShownProjectIds = StringUtils.getEditableStringSetFromPrefs(
                    prefs, KEY_LAST_SHOWN_PROJECT_IDS);
            if (lastShownProjectIds != null && lastShownProjectIds.contains(idString)) {
                lastShownProjectIds.remove(idString);
                if (lastShownProjectIds.isEmpty()) {
                    NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                    nm.cancel(0);
                    lastShownProjectIds = null;
                }
                prefs.edit()
                        .putStringSet(KEY_LAST_SHOWN_PROJECT_IDS, lastShownProjectIds)
                        .apply();
            }
        }
    }

    public NotificationsWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        java.util.Set<String> logins = app.getAllLogins();
        if (logins.isEmpty()) return Result.success();

        NotificationManagerCompat nm = NotificationManagerCompat.from(getApplicationContext());
        SharedPreferences prefs = getPrefs(getApplicationContext());

        // Track whether any notification was shown across all accounts for the summary.
        int totalUnseen = 0;
        boolean anyNew = false;

        for (String login : logins) {
            String token = app.getTokenForLogin(login);
            if (token == null) continue;

            String instanceUrl = app.getInstanceUrlForLogin(login);
            String apiBase = instanceUrl.endsWith("/")
                    ? instanceUrl + "api/v4/"
                    : instanceUrl + "/api/v4/";

            // Fetch pending todos for this account using its own token and instance URL.
            List<GitLabTodo> allTodos;
            try {
                Log.d(TAG, "Fetching todos for " + login + " at " + apiBase);
                GitLabTodoService service =
                        ServiceFactory.getForAccount(GitLabTodoService.class, apiBase, token);
                allTodos = service.listTodosByState("pending", 1, 100)
                        .map(ApiHelpers::throwOnFailure)
                        .blockingGet();
            } catch (Exception e) {
                Log.d(TAG, "Failed fetching todos for " + login, e);
                continue;
            }

            // Group todos by project.
            Map<Long, List<GitLabTodo>> todosByProject = new HashMap<>();
            for (GitLabTodo todo : allTodos) {
                long pid = todo.project != null ? todo.project.id : 0L;
                List<GitLabTodo> list = todosByProject.get(pid);
                if (list == null) { list = new ArrayList<>(); todosByProject.put(pid, list); }
                list.add(todo);
            }

            synchronized (sPrefsLock) {
                long lastCheck = prefs.getLong(keyLastCheck(login), 0);
                long lastSeen  = prefs.getLong(keyLastSeen(login), 0);
                Set<String> lastShown = StringUtils.getEditableStringSetFromPrefs(
                        prefs, keyShownIds(login));
                Set<String> newShown = new HashSet<>();
                boolean hasUnseen = false, hasNew = false;

                for (List<GitLabTodo> list : todosByProject.values()) {
                    for (GitLabTodo todo : list) {
                        long ts = parseIso8601Ms(todo.createdAt);
                        hasNew    |= ts > lastCheck;
                        hasUnseen |= ts > lastSeen;
                    }
                }

                if (!hasUnseen) continue;

                for (Map.Entry<Long, List<GitLabTodo>> entry : todosByProject.entrySet()) {
                    String pidStr = String.valueOf(entry.getKey());
                    showProjectTodoNotification(nm, entry.getValue(), lastCheck, login);
                    if (lastShown != null) lastShown.remove(pidStr);
                    newShown.add(pidStr);
                }

                // Cancel notifications for projects that no longer have pending todos.
                if (lastShown != null) {
                    for (String pid : lastShown) {
                        try { nm.cancel(notificationId(login, Long.parseLong(pid))); }
                        catch (NumberFormatException ignored) {}
                    }
                }

                prefs.edit()
                        .putLong(keyLastCheck(login), System.currentTimeMillis())
                        .putStringSet(keyShownIds(login), newShown)
                        .apply();

                totalUnseen += allTodos.size();
                anyNew |= hasNew;
            }
        }

        if (totalUnseen > 0) {
            showSummaryNotification(nm, totalUnseen, anyNew);
        }

        return Result.success();
    }

    private void showProjectTodoNotification(NotificationManagerCompat nm,
            List<GitLabTodo> todos, long lastCheck, String accountLogin) {
        if (todos.isEmpty()) return;
        final Context context = getApplicationContext();
        GitLabTodo first = todos.get(0);
        String projectName = first.project != null ? first.project.nameWithNamespace : "GitLab";
        long projectId = first.project != null ? first.project.id : 0L;
        final int id = notificationId(accountLogin, projectId);
        long when = parseIso8601Ms(first.createdAt);
        String text = context.getResources().getQuantityString(
                R.plurals.unread_notifications_summary_text, todos.size(), todos.size());

        // Intent includes the account login so HomeActivity can switch accounts on tap.
        Intent intent = HomeActivity.makeIntent(context, R.id.notifications)
                .putExtra(HomeActivity.EXTRA_NOTIFICATION_ACCOUNT, accountLogin)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent deleteIntent = PendingIntent.getService(context, id,
                NotificationHandlingService.makeHandleDismissIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification publicVersion = makeBaseBuilder()
                .setContentTitle(context.getString(R.string.unread_notifications_summary_title))
                .setContentText(text)
                .setNumber(todos.size())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationCompat.Builder builder = makeBaseBuilder()
                .setGroup(GROUP_ID_GITLAB)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setWhen(when)
                .setShowWhen(true)
                .setNumber(todos.size())
                .setPublicVersion(publicVersion)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(projectName)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setAutoCancel(true)
                .setContentText(text);

        // Set project avatar as the large icon so users can identify the project at a glance.
        String projectAvatarUrl = first.project != null ? first.project.avatarUrl : null;
        android.graphics.Bitmap projectIcon = loadProjectIcon(projectAvatarUrl, projectId);
        if (projectIcon != null) {
            builder.setLargeIcon(projectIcon);
        }

        boolean hasNewTodo = false;
        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle()
                .setBigContentTitle(projectName);
        for (int i = todos.size() - 1; i >= 0; i--) {
            GitLabTodo todo = todos.get(i);
            long todoTs = parseIso8601Ms(todo.createdAt);
            inbox.addLine(formatTodoMessage(context, todo));
            hasNewTodo = hasNewTodo || todoTs > lastCheck;
        }
        builder.setStyle(inbox);

        if (!hasNewTodo) builder.setOnlyAlertOnce(true);

        // "Mark as read" action — dismisses all notifications and updates seen state.
        PendingIntent markSeenIntent = PendingIntent.getService(context, 0,
                NotificationHandlingService.makeMarkNotificationsSeenIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(R.drawable.icon_notifications, "Mark as read", markSeenIntent);

        nm.notify(id, builder.build());
    }

    private void showSummaryNotification(NotificationManagerCompat nm,
            int totalCount, boolean hasNewTodo) {
        final Context context = getApplicationContext();

        String title = context.getString(R.string.unread_notifications_summary_title);
        String text = context.getResources().getQuantityString(
                R.plurals.unread_notifications_summary_text, totalCount, totalCount);

        Notification publicVersion = makeBaseBuilder()
                .setContentTitle(title)
                .setContentText(text)
                .setNumber(totalCount)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                HomeActivity.makeIntent(context, R.id.notifications),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent deleteIntent = PendingIntent.getService(context, 0,
                NotificationHandlingService.makeMarkNotificationsSeenIntent(context),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = makeBaseBuilder()
                .setGroup(GROUP_ID_GITLAB)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setContentTitle(title)
                .setContentText(text)
                .setPublicVersion(publicVersion)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setNumber(totalCount);

        if (!hasNewTodo) builder.setOnlyAlertOnce(true);

        nm.notify(0, builder.build());
    }

    private NotificationCompat.Builder makeBaseBuilder() {
        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_GITLAB_NOTIFICATIONS)
                .setSmallIcon(R.drawable.notification)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.octodroid));
    }

    /**
     * Fetches the project avatar for use as setLargeIcon.
     * The Todos API project sub-object omits avatar_url, so we fall back to
     * GET /projects/{id} which always includes it.
     */
    private static android.graphics.Bitmap loadProjectIcon(String avatarUrl, long projectId) {
        String url = avatarUrl;
        if ((url == null || url.isEmpty()) && projectId > 0) {
            // Resolve avatar_url via the full project endpoint.
            try {
                String apiUrl = com.gl4a.Gl4Application.get().getApiBaseUrl()
                        + "projects/" + projectId;
                okhttp3.OkHttpClient api = com.gl4a.ServiceFactory.getImageHttpClient();
                okhttp3.Request req = new okhttp3.Request.Builder().url(apiUrl).build();
                try (okhttp3.Response resp = api.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String json = resp.body().string();
                        int idx = json.indexOf("\"avatar_url\":\"");
                        if (idx >= 0) {
                            int start = idx + 14;
                            int end = json.indexOf("\"", start);
                            if (end > start) {
                                url = json.substring(start, end)
                                        .replace("\\/", "/");
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (url == null || url.isEmpty() || url.equals("null")) return null;
        try {
            okhttp3.OkHttpClient client = com.gl4a.ServiceFactory.getImageHttpClient();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                byte[] data = resp.body().bytes();
                return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Maps raw GitLab Todos API action_name values to natural English. */
    private static String formatActionVerb(String actionName) {
        if (actionName == null) return "notified you";
        switch (actionName) {
            case "mentioned":
            case "directly_addressed": return "mentioned you";
            case "assigned":            return "assigned you";
            case "review_requested":    return "requested your review";
            case "approval_required":   return "requested your approval";
            case "build_failed":        return "build failed";
            case "unmergeable":         return "MR cannot be merged";
            case "marked":              return "marked as todo";
            case "merge_train_removed": return "removed from merge train";
            default: return actionName.replace('_', ' ');
        }
    }

    /** Builds a one-line notification message: "Jay mentioned you in #34 Fix login bug". */
    private static String formatTodoMessage(Context ctx, GitLabTodo todo) {
        String authorName = (todo.author != null && todo.author.name() != null)
                ? todo.author.name() : "Someone";
        String verb = formatActionVerb(todo.actionName);
        String ref = "";
        if (todo.target != null) {
            String prefix = "MergeRequest".equals(todo.targetType) ? "!" : "#";
            ref = " in " + prefix + todo.target.iid;
            if (todo.target.title != null && !todo.target.title.isEmpty()) {
                ref += " " + todo.target.title;
            }
        }
        return authorName + " " + verb + ref;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(SettingsFragment.PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Parses an ISO 8601 timestamp string (e.g. "2024-01-15T12:34:56.000Z") to epoch millis.
     * Returns 0 if the string is null or cannot be parsed.
     */
    private static long parseIso8601Ms(String isoTimestamp) {
        if (isoTimestamp == null) return 0L;
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(utc);
            Date d = sdf.parse(isoTimestamp);
            return d != null ? d.getTime() : 0L;
        } catch (ParseException e) {
            try {
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf2.setTimeZone(utc);
                Date d = sdf2.parse(isoTimestamp);
                return d != null ? d.getTime() : 0L;
            } catch (ParseException e2) {
                try {
                    // Handle timezone-offset timestamps (e.g. +05:30) from self-hosted instances
                    SimpleDateFormat sdf3 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
                    sdf3.setTimeZone(utc);
                    Date d = sdf3.parse(isoTimestamp);
                    return d != null ? d.getTime() : 0L;
                } catch (ParseException e3) {
                    return 0L;
                }
            }
        }
    }
}
