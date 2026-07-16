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
    private static final String WORK_TAG = "job_notifications";

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
            getPrefs(context)
                    .edit()
                    .putLong(KEY_LAST_NOTIFICATION_SEEN, System.currentTimeMillis())
                    .putStringSet(KEY_LAST_SHOWN_PROJECT_IDS, null)
                    .apply();
        }
    }

    public static long getLastCheckTimestamp(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (!prefs.getBoolean(SettingsFragment.KEY_NOTIFICATIONS, false)) {
            return 0;
        }
        return prefs.getLong(KEY_LAST_NOTIFICATION_CHECK, 0);
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
        // Fetch pending GitLab todos grouped by project
        List<GitLabTodo> allTodos;
        try {
            Log.d(TAG, "Starting todo fetch in background");
            GitLabTodoService service = ServiceFactory.get(GitLabTodoService.class, true);
            allTodos = service.listTodosByState("pending", 1, 100)
                    .map(ApiHelpers::throwOnFailure)
                    .blockingGet();
        } catch (Exception e) {
            Log.d(TAG, "Failed fetching todos", e);
            return Result.failure();
        }

        // Group todos by project ID
        Map<Long, List<GitLabTodo>> todosByProject = new HashMap<>();
        for (GitLabTodo todo : allTodos) {
            long projectId = todo.project != null ? todo.project.id : 0L;
            List<GitLabTodo> list = todosByProject.get(projectId);
            if (list == null) {
                list = new ArrayList<>();
                todosByProject.put(projectId, list);
            }
            list.add(todo);
        }

        synchronized (sPrefsLock) {
            SharedPreferences prefs = getPrefs(getApplicationContext());
            long lastCheck = prefs.getLong(KEY_LAST_NOTIFICATION_CHECK, 0);
            long lastSeen = prefs.getLong(KEY_LAST_NOTIFICATION_SEEN, 0);
            Set<String> lastShownProjectIds =
                    StringUtils.getEditableStringSetFromPrefs(prefs, KEY_LAST_SHOWN_PROJECT_IDS);
            Set<String> newShownProjectIds = new HashSet<>();
            boolean hasUnseenTodo = false, hasNewTodo = false;

            for (List<GitLabTodo> list : todosByProject.values()) {
                for (GitLabTodo todo : list) {
                    long timestamp = parseIso8601Ms(todo.createdAt);
                    hasNewTodo |= timestamp > lastCheck;
                    hasUnseenTodo |= timestamp > lastSeen;
                }
            }

            Log.d(TAG, "Last check was " + new Date(lastCheck) + ", last seen " + new Date(lastSeen)
                    + " -> has new " + hasNewTodo + ", has unseen " + hasUnseenTodo);

            if (!hasUnseenTodo) {
                return Result.success();
            }

            NotificationManagerCompat nm =
                    NotificationManagerCompat.from(getApplicationContext());

            showSummaryNotification(nm, todosByProject, allTodos.size(), hasNewTodo);
            for (Map.Entry<Long, List<GitLabTodo>> entry : todosByProject.entrySet()) {
                String projectIdStr = String.valueOf(entry.getKey());
                showProjectTodoNotification(nm, entry.getValue(), lastCheck);
                if (lastShownProjectIds != null) {
                    lastShownProjectIds.remove(projectIdStr);
                }
                newShownProjectIds.add(projectIdStr);
            }

            if (lastShownProjectIds != null) {
                for (String projectId : lastShownProjectIds) {
                    nm.cancel(Integer.parseInt(projectId));
                }
            }

            prefs.edit()
                    .putLong(KEY_LAST_NOTIFICATION_CHECK, System.currentTimeMillis())
                    .putStringSet(KEY_LAST_SHOWN_PROJECT_IDS, newShownProjectIds)
                    .apply();
        }

        return Result.success();
    }

    private void showProjectTodoNotification(NotificationManagerCompat nm,
            List<GitLabTodo> todos, long lastCheck) {
        if (todos.isEmpty()) return;
        final Context context = getApplicationContext();
        GitLabTodo first = todos.get(0);
        String projectName = first.project != null ? first.project.nameWithNamespace : "GitLab";
        final int id = first.project != null ? (int) first.project.id : 0;
        long when = parseIso8601Ms(first.createdAt);
        String text = context.getResources().getQuantityString(
                R.plurals.unread_notifications_summary_text,
                todos.size(), todos.size());

        Intent intent = HomeActivity.makeIntent(context, R.id.notifications)
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

        boolean hasNewTodo = false;
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle("")
                .setConversationTitle(projectName);
        for (int i = todos.size() - 1; i >= 0; i--) {
            GitLabTodo todo = todos.get(i);
            long todoTs = parseIso8601Ms(todo.createdAt);
            String todoBody = todo.body != null ? todo.body : todo.title();
            style.addMessage(todoBody, todoTs, todo.actionName);
            hasNewTodo = hasNewTodo || todoTs > lastCheck;
        }
        builder.setStyle(style);

        if (!hasNewTodo) {
            builder.setOnlyAlertOnce(true);
        }

        nm.notify(id, builder.build());
    }

    private void showSummaryNotification(NotificationManagerCompat nm,
            Map<Long, List<GitLabTodo>> todosByProject, int totalCount,
            boolean hasNewTodo) {
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

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle(builder)
                .setBigContentTitle(text);
        for (Map.Entry<Long, List<GitLabTodo>> entry : todosByProject.entrySet()) {
            List<GitLabTodo> list = entry.getValue();
            if (list.isEmpty()) continue;
            GitLabTodo first = list.get(0);
            String projectName = first.project != null ? first.project.nameWithNamespace : "GitLab";

            final TextAppearanceSpan notificationPrimarySpan =
                    new TextAppearanceSpan(context, R.style.TextAppearance_NotificationEmphasized);
            SpannableStringBuilder line = new SpannableStringBuilder(projectName).append(" ");
            final int emphasisEnd;

            if (list.size() == 1) {
                line.append(first.actionName != null ? first.actionName : "");
                emphasisEnd = line.length();
                String firstBody = first.body != null ? first.body : first.title();
                line.append(" ").append(firstBody != null ? firstBody : "");
            } else {
                emphasisEnd = line.length();
                line.append(context.getResources().getQuantityString(R.plurals.notification,
                        list.size(), list.size()));
            }

            line.setSpan(notificationPrimarySpan, 0, emphasisEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            inboxStyle.addLine(line);
        }
        builder.setStyle(inboxStyle);

        if (!hasNewTodo) {
            builder.setOnlyAlertOnce(true);
        }

        nm.notify(0, builder.build());
    }

    private NotificationCompat.Builder makeBaseBuilder() {
        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_GITLAB_NOTIFICATIONS)
                .setSmallIcon(R.drawable.notification)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.octodroid));
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
