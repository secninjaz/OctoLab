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
    // Include applicationId so debug/internal/release installs don't share
    // the same WorkManager unique work ID and cancel each other's worker.
    public static final String WORK_TAG = "job_notifications_" + com.gl4a.BuildConfig.APPLICATION_ID;
    private static final String LEGACY_WORK_NAME = "job_notifications";

    private static final String KEY_LAST_NOTIFICATION_CHECK = "last_notification_check";
    private static final String KEY_LAST_NOTIFICATION_SEEN = "last_notification_seen";
    private static final String KEY_LAST_SHOWN_PROJECT_IDS = "last_notification_repo_ids";
    private static final String KEY_SHOWN_SUMMARY_IDS = "last_notification_summary_ids";

    /** SharedPreferences key storing the last 20 worker run log lines (newest first). */
    public static final String KEY_WORKER_LOG = "worker_run_log";
    private static final int MAX_LOG_ENTRIES = 20;

    private static final Object sPrefsLock = new Object();
    // Android drops an app's notifications beyond about 50; the newest to-dos are shown and the
    // summary counts them (#178)
    private static final int MAX_TODO_NOTIFICATIONS_PER_ACCOUNT = 20;

    /** Registers the check unless it already is: e.g. on every start of the app (#277). */
    public static void schedule(Context context, int intervalMinutes) {
        schedule(context, intervalMinutes, false);
    }

    /**
     * Registers the check.
     *
     * @param newInterval the interval was changed: a registered check takes it. Otherwise a
     *                    registered check is kept as it is, as re-registering it stopped and
     *                    restarted a check that had just started (#277)
     */
    public static void schedule(Context context, int intervalMinutes, boolean newInterval) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                NotificationsWorker.class, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build();
        Log.d(TAG, "Scheduling notification fetch to happen every " + intervalMinutes + " min");
        // Never REPLACE: it cancelled and restarted the check on every start of the app, Android
        // starting it for a check included, so each check ran twice at once (#277)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_TAG, newInterval
                ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, request);
        cancelLegacyWork(context);
    }

    public static void cancel(Context context) {
        Log.d(TAG, "Canceling notification fetch");
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG);
        cancelLegacyWork(context);
    }

    /**
     * The check as registered before 1.3.1, under a name without the app ID: renaming it left it
     * running beside the new one, so every check ran twice since (#277).
     */
    private static void cancelLegacyWork(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME);
        WorkManager.getInstance(context).cancelAllWorkByTag(LEGACY_WORK_NAME);
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

    /** Marks all accounts' notifications as seen. Pass {@code cancelAll=true} when the user
     *  opens the app (clears every system notification); pass {@code false} when called from
     *  a per-notification action so other projects' system notifications are not dismissed. */
    public static void markNotificationsAsSeen(Context context, boolean cancelAll) {
        if (cancelAll) {
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) notificationManager.cancelAll();
        }

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
                editor.remove(keyShownSummaries(login));
            }
            // Also update legacy global key for any older code paths.
            editor.putLong(KEY_LAST_NOTIFICATION_SEEN, now);
            editor.putStringSet(KEY_LAST_SHOWN_PROJECT_IDS, null);
            editor.apply();
        }
    }

    /**
     * The To-dos list was opened: to-dos in it from now on won't be notified, but notifications
     * already showing are left as they are, and still tracked so that they go once their to-do
     * is done (#178).
     */
    public static void markTodoListSeen(Context context) {
        synchronized (sPrefsLock) {
            SharedPreferences prefs = getPrefs(context);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            for (String login : com.gl4a.Gl4Application.get().getAllLogins()) {
                editor.putLong(keyLastSeen(login), now);
            }
            editor.putLong(KEY_LAST_NOTIFICATION_SEEN, now);
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

    private static String keyShownSummaries(String login) {
        return KEY_SHOWN_SUMMARY_IDS + "_" + login;
    }

    /** A to-do's notification was swiped away or acted on: don't post it again (#178). */
    public static void handleNotificationDismiss(Context context, int id) {
        SharedPreferences prefs = getPrefs(context);
        String idString = String.valueOf(id);

        synchronized (sPrefsLock) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String login : com.gl4a.Gl4Application.get().getAllLogins()) {
                Set<String> shown = StringUtils.getEditableStringSetFromPrefs(
                        prefs, keyShownIds(login));
                if (shown == null) continue;
                if (shown.remove(idString)) {
                    editor.putStringSet(keyShownIds(login), shown);
                }
            }
            editor.apply();
            // Android removes a project's summary itself once its last to-do is gone
        }
        forgetParticipating(context, id);
    }

    /**
     * The real cause of an error, which RxJava and Retrofit wrap (#277): the first network error
     * in its causes, else the innermost cause. Test builds add a debugging cause of their own
     * (Traceur's) at the very end, which is skipped.
     */
    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.IOException) return t;
            if (!t.getClass().getName().contains("traceur")) root = t;
        }
        return root;
    }

    /**
     * Why a check failed, for the Worker log: the real cause, not its wrapper (#277), e.g.
     * "no connection (UnknownHostException)" or "timeout (SocketTimeoutException)".
     */
    private static String failureReason(Throwable error) {
        Throwable root = rootCause(error);
        String name = root.getClass().getSimpleName();
        String what;
        if (root instanceof java.net.UnknownHostException) {
            what = "no connection";
        } else if (root instanceof java.io.InterruptedIOException) {
            what = "timeout";
        } else if (root instanceof java.net.ConnectException
                || root instanceof java.net.NoRouteToHostException) {
            what = "can't connect";
        } else if (root instanceof javax.net.ssl.SSLException) {
            what = "secure connection failed";
        } else if (root instanceof java.io.IOException) {
            what = "connection error";
        } else {
            String message = root.getMessage();
            if (message == null || message.isEmpty()) return name;
            if (message.length() > 80) message = message.substring(0, 80) + "…";
            return name + ": " + message;
        }
        return what + " (" + name + ")";
    }

    private static String keyParticipatingShown(String login) {
        return "participating_shown_" + login;
    }

    /**
     * A participating comment's notification (#267) was opened: it isn't shown again after an
     * update or restart (#275).
     */
    public static void handleParticipatingOpened(Context context, long todoId) {
        // Whichever account it was: the ID includes the account, so only its own matches
        for (String login : com.gl4a.Gl4Application.get().getAllLogins()) {
            forgetParticipating(context, todoNotificationId(login, todoId));
        }
    }

    /**
     * A participating comment read or deleted in the app (#280): its notification, if still
     * showing, goes, and isn't shown again.
     */
    public static void cancelParticipating(Context context, String login, long todoId) {
        int id = todoNotificationId(login, todoId);
        forgetParticipating(context, id);
        NotificationManagerCompat.from(context).cancel(id);
    }

    /** Drops a participating comment's notification from those shown again if lost (#275). */
    private static void forgetParticipating(Context context, int id) {
        SharedPreferences prefs = getPrefs(context);
        String prefix = id + "|";
        synchronized (sPrefsLock) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String login : com.gl4a.Gl4Application.get().getAllLogins()) {
                Set<String> shown = StringUtils.getEditableStringSetFromPrefs(
                        prefs, keyParticipatingShown(login));
                if (shown == null) continue;
                boolean removed = false;
                for (java.util.Iterator<String> it = shown.iterator(); it.hasNext(); ) {
                    if (it.next().startsWith(prefix)) {
                        it.remove();
                        removed = true;
                    }
                }
                if (removed) editor.putStringSet(keyParticipatingShown(login), shown);
            }
            editor.apply();
        }
    }

    /**
     * Shows again the participating comments' notifications (#267) that Android removed on an
     * app update or a restart, as to-dos' are (#275). One gone without either was opened by the
     * user, also when the app wasn't running and the check started as it opened, so it's dropped.
     * They're kept with what they show, so this needs no requests.
     */
    private void restoreParticipating(NotificationManagerCompat nm, SharedPreferences prefs,
            String login, Map<Long, android.graphics.Bitmap> logos,
            Map<String, android.graphics.Bitmap> icons) {
        Set<String> shown = StringUtils.getEditableStringSetFromPrefs(
                prefs, keyParticipatingShown(login));
        com.gl4a.utils.DebugLogger log = com.gl4a.utils.DebugLogger.get();
        if (shown == null || shown.isEmpty()) {
            log.log("Worker", "Comments [" + login + "]: none kept to show again");
            return;
        }
        Set<Integer> active = new HashSet<>();
        NotificationManager manager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        for (android.service.notification.StatusBarNotification sbn
                : manager.getActiveNotifications()) {
            active.add(sbn.getId());
        }
        long bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        long updateTime = 0;
        try {
            updateTime = getApplicationContext().getPackageManager().getPackageInfo(
                    getApplicationContext().getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        com.squareup.moshi.JsonAdapter<GitLabTodo> adapter =
                ServiceFactory.moshi().adapter(GitLabTodo.class);
        List<Integer> opened = new ArrayList<>();
        int restored = 0;
        int showing = 0;
        for (String entry : shown) {
            // "id|created|shown|json"; builds before this had no "shown"
            String[] parts = entry.split("\\|", 4);
            if (parts.length < 3) continue;
            boolean withShown = parts.length == 4 && !parts[2].startsWith("{");
            try {
                int nid = Integer.parseInt(parts[0]);
                if (active.contains(nid)) {
                    showing++;
                    continue;
                }
                long shownAt = withShown ? Long.parseLong(parts[2]) : 0;
                if (shownAt >= bootTime && shownAt >= updateTime) {
                    opened.add(nid);
                    continue;
                }
                GitLabTodo comment = adapter.fromJson(withShown ? parts[3]
                        : entry.substring(parts[0].length() + parts[1].length() + 2));
                if (comment == null) continue;
                showTodoNotification(nm, comment, nid, false, login, "participating:" + login,
                        logos, icons);
                rememberParticipating(prefs, login, nid, comment);
                restored++;
            } catch (Exception e) {
                Log.d(TAG, "Couldn't show a participating comment again", e);
                log.error("Worker", "Comments [" + login + "]: couldn't show one again", e);
            }
        }
        // What happened to the kept ones, for the debug log (#280)
        log.log("Worker", "Comments [" + login + "]: restored " + restored + ", still showing "
                + showing + ", dropped " + opened.size() + " (opened or dismissed)");
        for (int nid : opened) {
            forgetParticipating(getApplicationContext(), nid);
        }
    }

    /** Keeps a participating comment's notification, to show it again if lost (#275). */
    private static void rememberParticipating(SharedPreferences prefs, String login, int nid,
            GitLabTodo comment) {
        String json;
        try {
            json = ServiceFactory.moshi().adapter(GitLabTodo.class).toJson(comment);
        } catch (Exception e) {
            return;
        }
        synchronized (sPrefsLock) {
            Set<String> shown = StringUtils.getEditableStringSetFromPrefs(
                    prefs, keyParticipatingShown(login));
            if (shown == null) shown = new HashSet<>();
            String prefix = nid + "|";
            for (java.util.Iterator<String> it = shown.iterator(); it.hasNext(); ) {
                if (it.next().startsWith(prefix)) it.remove();
            }
            shown.add(nid + "|" + parseIso8601Ms(comment.createdAt) + "|"
                    + System.currentTimeMillis() + "|" + json);
            // At most as many as are shown at once: the oldest go first
            while (shown.size() > MAX_TODO_NOTIFICATIONS_PER_ACCOUNT) {
                String oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (String entry : shown) {
                    String[] parts = entry.split("\\|", 3);
                    long time = 0;
                    try {
                        time = Long.parseLong(parts[1]);
                    } catch (Exception ignored) {
                    }
                    if (time < oldestTime) {
                        oldestTime = time;
                        oldest = entry;
                    }
                }
                shown.remove(oldest);
            }
            prefs.edit().putStringSet(keyParticipatingShown(login), shown).apply();
        }
    }

    // One notification per to-do (#178): a stable ID from the account and the to-do; never 0,
    // which older builds used for their one summary
    private static int todoNotificationId(String login, long todoId) {
        int id = (login + ":" + todoId).hashCode();
        return id == 0 || id == -1 ? 1 : id;
    }

    // Each project's to-dos are a group of their own, under a summary with the project logo
    private static int projectSummaryId(String login, long projectId) {
        int id = ("project:" + login + ":" + projectId).hashCode();
        return id == 0 || id == -1 ? 2 : id;
    }

    private static String projectGroupKey(String login, long projectId) {
        return GROUP_ID_GITLAB + ":" + login + ":" + projectId;
    }

    public NotificationsWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        com.gl4a.Gl4Application app = com.gl4a.Gl4Application.get();
        java.util.Set<String> logins = app.getAllLogins();
        long runStart = System.currentTimeMillis();
        if (logins.isEmpty()) {
            appendWorkerLog(getApplicationContext(), runStart, "No accounts — skipped", 0, false);
            return Result.success();
        }

        NotificationManagerCompat nm = NotificationManagerCompat.from(getApplicationContext());
        SharedPreferences prefs = getPrefs(getApplicationContext());

        // Track whether any notification was shown across all accounts for the summary.
        int totalUnseen = 0;
        // For the summary: an "OK" when no account could be reached misled (#277)
        int checked = 0;
        int failed = 0;
        boolean anyNew = false;

        for (String login : logins) {
            if (isStopped()) {
                // Android stopped this check (and runs it again): don't carry on beside it (#277)
                appendWorkerLog(getApplicationContext(), runStart, "Stopped by Android", 0, false);
                return Result.success();
            }
            String token = app.getTokenForLogin(login);
            if (token == null) continue;
            checked++;

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
                retrofit2.Response<List<GitLabTodo>> response =
                        service.listTodosByState("pending", 1, 100).blockingGet();
                allTodos = ApiHelpers.throwOnFailure(response);
                // Its count, for the drawer's accounts list (#290)
                com.gl4a.utils.TodoCounts.set(getApplicationContext(), login,
                        com.gl4a.utils.TodoCounts.fromResponse(response, allTodos));
            } catch (Exception e) {
                Log.d(TAG, "Failed fetching todos for " + login, e);
                com.gl4a.utils.DebugLogger.get().error("Worker",
                        "Checking to-dos failed for " + login, rootCause(e));
                appendWorkerLog(getApplicationContext(), System.currentTimeMillis(),
                        "FAILED [" + login + "]: " + failureReason(e), 0, false);
                failed++;
                continue;
            }

            // New comments on issues and MRs the user participates in (#267)
            if (prefs.getBoolean(SettingsFragment.KEY_NOTIFY_PARTICIPATING, true)) {
                try {
                    int comments = notifyParticipatingComments(nm, prefs, login, apiBase, token);
                    totalUnseen += comments;
                    anyNew |= comments > 0;
                } catch (com.gl4a.utils.Participation.UnreachableException e) {
                    // Not marked as checked, so the next check covers this one (#275)
                    Log.d(TAG, "GitLab not reachable for participating comments of " + login, e);
                    com.gl4a.utils.DebugLogger.get().error("Worker",
                            "Checking comments postponed for " + login, rootCause(e));
                    appendWorkerLog(getApplicationContext(), System.currentTimeMillis(),
                            "Comments [" + login + "]: GitLab not reachable ("
                                    + failureReason(e) + "), next check", 0, false);
                } catch (Exception e) {
                    Log.d(TAG, "Checking participating comments failed for " + login, e);
                }
            }

            synchronized (sPrefsLock) {
                long lastCheck = prefs.getLong(keyLastCheck(login), 0);
                long lastSeen  = prefs.getLong(keyLastSeen(login), 0);
                Set<String> lastShown = StringUtils.getEditableStringSetFromPrefs(
                        prefs, keyShownIds(login));
                Set<String> newShown = new HashSet<>();
                boolean hasUnseen, hasNew = false;
                for (GitLabTodo todo : allTodos) {
                    hasNew |= parseIso8601Ms(todo.createdAt) > lastCheck;
                }

                // Always record the successful check time so "Last synced" reflects
                // the actual last API poll, not just the last notification.
                prefs.edit().putLong(keyLastCheck(login), System.currentTimeMillis()).apply();

                // One notification per to-do, newest first, as OctoDroid did (#178): those
                // newer than when the user last looked, plus those still showing
                List<GitLabTodo> sorted = new ArrayList<>(allTodos);
                java.util.Collections.sort(sorted, (a, b) -> Long.compare(
                        parseIso8601Ms(b.createdAt), parseIso8601Ms(a.createdAt)));
                Map<Long, android.graphics.Bitmap> logos = new HashMap<>();
                Map<String, android.graphics.Bitmap> icons = new HashMap<>();
                // Posted to-dos per project, newest first, in the order projects first appear
                Map<Long, List<GitLabTodo>> postedByProject = new java.util.LinkedHashMap<>();
                Map<Long, Boolean> projectHasNew = new HashMap<>();
                int posted = 0;
                for (GitLabTodo todo : sorted) {
                    if (posted >= MAX_TODO_NOTIFICATIONS_PER_ACCOUNT) break;
                    int nid = todoNotificationId(login, todo.id);
                    String nidStr = String.valueOf(nid);
                    long ts = parseIso8601Ms(todo.createdAt);
                    boolean showing = lastShown != null && lastShown.contains(nidStr);
                    if (ts <= lastSeen && !showing) continue;
                    long pid = todo.project != null ? todo.project.id : 0L;
                    showTodoNotification(nm, todo, nid, ts > lastCheck, login,
                            projectGroupKey(login, pid), logos, icons);
                    List<GitLabTodo> group = postedByProject.get(pid);
                    if (group == null) {
                        group = new ArrayList<>();
                        postedByProject.put(pid, group);
                    }
                    group.add(todo);
                    if (ts > lastCheck) projectHasNew.put(pid, true);
                    if (lastShown != null) lastShown.remove(nidStr);
                    newShown.add(nidStr);
                    posted++;
                }
                hasUnseen = !newShown.isEmpty();

                // Cancel notifications of to-dos no longer pending (done elsewhere)
                if (lastShown != null) {
                    for (String nid : lastShown) {
                        try { nm.cancel(Integer.parseInt(nid)); }
                        catch (NumberFormatException ignored) {}
                    }
                }

                // One summary per project: its logo and name, collapsing the to-dos below it
                Set<String> lastSummaries = StringUtils.getEditableStringSetFromPrefs(
                        prefs, keyShownSummaries(login));
                Set<String> newSummaries = new HashSet<>();
                for (Map.Entry<Long, List<GitLabTodo>> e : postedByProject.entrySet()) {
                    int sid = projectSummaryId(login, e.getKey());
                    showProjectSummaryNotification(nm, sid, e.getValue(),
                            projectHasNew.containsKey(e.getKey()), login,
                            projectGroupKey(login, e.getKey()), logos);
                    newSummaries.add(String.valueOf(sid));
                    if (lastSummaries != null) lastSummaries.remove(String.valueOf(sid));
                }
                if (lastSummaries != null) {
                    for (String sid : lastSummaries) {
                        try { nm.cancel(Integer.parseInt(sid)); }
                        catch (NumberFormatException ignored) {}
                    }
                }

                prefs.edit()
                        .putStringSet(keyShownIds(login), newShown)
                        .putStringSet(keyShownSummaries(login), newSummaries)
                        .apply();

                if (!hasUnseen) continue;

                totalUnseen += newShown.size();
                anyNew |= hasNew;
            }
        }

        // Older builds put every to-do under one app-wide summary (ID 0)
        nm.cancel(0);

        String summary;
        if (checked > 0 && failed == checked) {
            summary = "FAILED — no account reachable";
        } else {
            summary = (totalUnseen > 0 ? "Notified: " + totalUnseen + " pending"
                    : "OK — no new todos")
                    + (failed > 0 ? " (" + failed + " of " + checked + " failed)" : "");
        }
        appendWorkerLog(getApplicationContext(), runStart, summary, totalUnseen, anyNew);
        return Result.success();
    }

    private static String keyParticipatingCheck(String login) {
        return "participating_last_check_" + login;
    }

    /**
     * Notifies new comments by others on the issues and MRs the account participates in, as
     * the shared definition has it (#267): only items updated since the last check, and only
     * comments made since then. The first check only sets that starting point. A comment that
     * mentions the user already comes as a to-do, so it's left to that.
     *
     * @return how many were notified
     */
    private int notifyParticipatingComments(NotificationManagerCompat nm,
            SharedPreferences prefs, String login, String apiBase, String token) {
        long now = System.currentTimeMillis();
        long since = prefs.getLong(keyParticipatingCheck(login), 0);
        if (since <= 0) {
            prefs.edit().putLong(keyParticipatingCheck(login), now).apply();
            return 0;
        }
        Context context = getApplicationContext();
        com.gl4a.utils.Participation participation =
                com.gl4a.utils.Participation.forAccount(context, login, apiBase, token);
        String sinceIso = com.gl4a.utils.Participation.isoTime(since);
        List<com.gl4a.gitlab.model.GitLabIssue> changed =
                new ArrayList<>(participation.items(false, sinceIso, false));
        changed.addAll(participation.items(true, sinceIso, false));

        com.gl4a.gitlab.service.GitLabIssueService issues = ServiceFactory.getForAccount(
                com.gl4a.gitlab.service.GitLabIssueService.class, apiBase, token);
        com.gl4a.gitlab.service.GitLabMergeRequestService mrs = ServiceFactory.getForAccount(
                com.gl4a.gitlab.service.GitLabMergeRequestService.class, apiBase, token);
        com.gl4a.gitlab.service.GitLabProjectService projects = ServiceFactory.getForAccount(
                com.gl4a.gitlab.service.GitLabProjectService.class, apiBase, token);
        Map<Long, com.gl4a.gitlab.model.GitLabProject> projectsById = new HashMap<>();
        Map<Long, android.graphics.Bitmap> logos = new HashMap<>();
        Map<String, android.graphics.Bitmap> icons = new HashMap<>();
        restoreParticipating(nm, prefs, login, logos, icons);
        int posted = 0;
        for (com.gl4a.gitlab.model.GitLabIssue item : changed) {
            if (posted >= MAX_TODO_NOTIFICATIONS_PER_ACCOUNT) break;
            boolean mergeRequest = item.webUrl != null
                    && item.webUrl.contains("/-/merge_requests/");
            List<com.gl4a.gitlab.model.GitLabComment> notes;
            try {
                notes = (mergeRequest
                        ? mrs.getComments(item.projectId, item.iid, "desc", 1, 20)
                        : issues.getComments(item.projectId, item.iid, "desc", 1, 20))
                        .map(ApiHelpers::throwOnFailure)
                        .blockingGet();
            } catch (Exception e) {
                if (com.gl4a.utils.Participation.isUnreachable(e)) {
                    throw new com.gl4a.utils.Participation.UnreachableException(e);
                }
                continue;
            }
            for (com.gl4a.gitlab.model.GitLabComment note : notes) {
                if (posted >= MAX_TODO_NOTIFICATIONS_PER_ACCOUNT) break;
                if (note.system || note.author == null
                        || ApiHelpers.loginEquals(note.author, login)
                        || com.gl4a.utils.Participation.isoMillis(note.createdAt) <= since) {
                    continue;
                }
                String body = note.body();
                if (body != null && body.contains("@" + login)) continue;
                com.gl4a.gitlab.model.GitLabProject project = projectsById.get(item.projectId);
                if (project == null && !projectsById.containsKey(item.projectId)) {
                    try {
                        project = projects.getProject(item.projectId)
                                .map(ApiHelpers::throwOnFailure).blockingGet();
                    } catch (Exception ignored) {
                    }
                    projectsById.put(item.projectId, project);
                }
                // Shown as the to-dos are, so it looks and opens the same: at the comment
                GitLabTodo comment = new GitLabTodo();
                comment.id = -note.id;
                comment.project = project;
                comment.author = note.author;
                comment.actionName = "commented";
                comment.targetType = mergeRequest ? "MergeRequest" : "Issue";
                comment.target = item;
                comment.targetUrl = item.webUrl + "#note_" + note.id;
                comment.body = body;
                comment.createdAt = note.createdAt;
                int nid = todoNotificationId(login, comment.id);
                showTodoNotification(nm, comment, nid, true, login, "participating:" + login,
                        logos, icons);
                rememberParticipating(prefs, login, nid, comment);
                // Kept to be seen again in To-dos | Participating, unread (#280)
                com.gl4a.utils.ParticipatingInbox.get(context).add(login, comment);
                posted++;
            }
        }
        prefs.edit().putLong(keyParticipatingCheck(login), now).apply();
        return posted;
    }

    /** Prepends a log entry to KEY_WORKER_LOG, keeping at most MAX_LOG_ENTRIES. */
    public static void appendWorkerLog(Context ctx, long startMs, String summary,
            int unseen, boolean anyNew) {
        SharedPreferences prefs = getPrefs(ctx);
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(startMs));
        String entry = ts + " | " + summary;
        String existing = prefs.getString(KEY_WORKER_LOG, "");
        String[] lines = existing.isEmpty() ? new String[0] : existing.split("\n", -1);
        StringBuilder sb = new StringBuilder(entry);
        int kept = 0;
        for (String line : lines) {
            if (!line.isEmpty() && kept < MAX_LOG_ENTRIES - 1) {
                sb.append("\n").append(line);
                kept++;
            }
        }
        prefs.edit().putString(KEY_WORKER_LOG, sb.toString()).apply();
    }

    /** Returns the stored worker log string, or empty string if none. */
    public static String getWorkerLog(Context ctx) {
        return getPrefs(ctx).getString(KEY_WORKER_LOG, "");
    }

    /**
     * One expandable notification per to-do, like OctoDroid (#178): the project as title,
     * "Issue: <title>" collapsed, and expanded the action ("Jay mentioned you in #34"), the
     * title and GitLab's text for the to-do. Tapping opens the to-do's target in-app with its
     * account (and marks it done, as in the To-dos list); "Mark as done" acts on it alone.
     */
    private void showTodoNotification(NotificationManagerCompat nm, GitLabTodo todo, int id,
            boolean isNew, String accountLogin, String groupKey,
            Map<Long, android.graphics.Bitmap> logos, Map<String, android.graphics.Bitmap> icons) {
        final Context context = getApplicationContext();
        String projectName = projectName(context, todo);
        String title = todo.title();

        // All from what GitLab returns for the to-do: who did what where, the target's title
        // once, then GitLab's body (the comment) when it isn't just that title again
        StringBuilder expanded = new StringBuilder(formatTodoMessage(context, todo));
        if (todo.target != null && title != null && !title.isEmpty()) {
            expanded.append("\n").append(title);
        }
        String body = todo.body != null ? todo.body.trim() : "";
        if (!body.isEmpty() && !body.equals(title)) {
            expanded.append("\n\n").append(body.length() > 400
                    ? body.substring(0, 400) + "\u2026" : body);
        }

        Intent intent = HomeActivity.makeIntent(context, R.id.notifications)
                .putExtra(HomeActivity.EXTRA_NOTIFICATION_ACCOUNT, accountLogin)
                .putExtra(HomeActivity.EXTRA_OPEN_URL, todo.targetUrl)
                .putExtra(HomeActivity.EXTRA_TODO_ID, todo.id)
                // Distinct data per to-do, so each PendingIntent keeps its own extras
                .setData(android.net.Uri.parse("octolab-todo://" + accountLogin + "/" + todo.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent deleteIntent = PendingIntent.getService(context, id,
                NotificationHandlingService.makeHandleDismissIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent doneIntent = PendingIntent.getService(context, id,
                NotificationHandlingService.makeMarkTodoDoneIntent(context, id, accountLogin,
                        todo.id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification publicVersion = makeBaseBuilder()
                .setContentTitle(context.getString(R.string.unread_notifications_summary_title))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationCompat.Builder builder = makeBaseBuilder()
                .setGroup(groupKey)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setWhen(parseIso8601Ms(todo.createdAt))
                .setShowWhen(true)
                .setPublicVersion(publicVersion)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(projectName)
                .setContentText(collapsedLine(todo))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(projectName)
                        .bigText(expanded.toString()))
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setAutoCancel(true);
        // A comment on a participated item (#267) has no to-do to mark done
        if (todo.id > 0) {
            builder.addAction(R.drawable.icon_notifications,
                    context.getString(R.string.mark_todo_done), doneIntent);
        } else {
            // Read without opening it, in the app's Participating list (#280)
            builder.addAction(R.drawable.icon_notifications,
                    context.getString(R.string.mark_as_read), PendingIntent.getService(context,
                            id, NotificationHandlingService.makeMarkParticipatingReadIntent(
                                    context, id, accountLogin, -todo.id),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        if (!isNew) builder.setOnlyAlertOnce(true);

        // Who and where in Android's one icon slot: the round avatar of whoever caused the
        // to-do, as GitLab gives it, with the project logo as a badge; made once per run
        if (todo.author != null) {
            String key = todo.author.id + ":" + (todo.project != null ? todo.project.id : 0);
            android.graphics.Bitmap icon = icons.get(key);
            if (icon == null && !icons.containsKey(key)) {
                icon = AvatarHandler.withProjectBadge(
                        AvatarHandler.loadUserAvatarSynchronously(context, todo.author),
                        projectLogo(todo, accountLogin, logos), largeIconSize(context));
                icons.put(key, icon);
            }
            builder.setLargeIcon(icon);
        }

        nm.notify(id, builder.build());
    }

    /** A project's summary: its logo and name, with one line per to-do under it. */
    private void showProjectSummaryNotification(NotificationManagerCompat nm, int id,
            List<GitLabTodo> todos, boolean hasNew, String accountLogin, String groupKey,
            Map<Long, android.graphics.Bitmap> logos) {
        final Context context = getApplicationContext();
        GitLabTodo newest = todos.get(0);
        String projectName = projectName(context, newest);

        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle()
                .setBigContentTitle(projectName);
        for (GitLabTodo todo : todos) inbox.addLine(collapsedLine(todo));

        Intent intent = HomeActivity.makeIntent(context, R.id.notifications)
                .putExtra(HomeActivity.EXTRA_NOTIFICATION_ACCOUNT, accountLogin)
                .setData(android.net.Uri.parse("octolab-todos://" + accountLogin + "/" + id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification publicVersion = makeBaseBuilder()
                .setContentTitle(context.getString(R.string.unread_notifications_summary_title))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationCompat.Builder builder = makeBaseBuilder()
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setWhen(parseIso8601Ms(newest.createdAt))
                .setShowWhen(true)
                .setPublicVersion(publicVersion)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(projectName)
                .setContentText(collapsedLine(newest))
                .setStyle(inbox)
                .setNumber(todos.size())
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL);
        if (!hasNew) builder.setOnlyAlertOnce(true);

        builder.setLargeIcon(projectLogo(newest, accountLogin, logos));

        nm.notify(id, builder.build());
    }

    /** The one shared project logo, as in the app (#176); fetched once per project per run. */
    private android.graphics.Bitmap projectLogo(GitLabTodo todo, String accountLogin,
            Map<Long, android.graphics.Bitmap> logos) {
        if (todo.project == null) return null;
        android.graphics.Bitmap logo = logos.get(todo.project.id);
        if (logo == null && !logos.containsKey(todo.project.id)) {
            logo = AvatarHandler.loadProjectLogoSynchronously(todo.project,
                    largeIconSize(getApplicationContext()), apiBaseForLogin(accountLogin));
            logos.put(todo.project.id, logo);
        }
        return logo;
    }

    private static int largeIconSize(Context context) {
        return context.getResources()
                .getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
    }

    private static String projectName(Context context, GitLabTodo todo) {
        return todo.project != null && todo.project.nameWithNamespace != null
                ? todo.project.nameWithNamespace : context.getString(R.string.app_name);
    }

    /** "Issue: <title>", with GitLab's own type of the target; just the title without one. */
    private static String collapsedLine(GitLabTodo todo) {
        String type = todoTypeLabel(todo);
        String title = todo.title();
        return type != null ? type + ": " + title : title;
    }

    /**
     * GitLab's type of the to-do's target in words: the work item type it gives ("TASK" →
     * "Task"), else its target_type ("MergeRequest" → "Merge request"); null without either.
     */
    private static String todoTypeLabel(GitLabTodo todo) {
        String raw = todo.target != null && todo.target.type != null
                ? todo.target.type : todo.targetType;
        if (raw == null || raw.isEmpty()) return null;
        String words = raw.indexOf('_') >= 0 || raw.equals(raw.toUpperCase(java.util.Locale.ROOT))
                ? raw.replace('_', ' ')
                : raw.replaceAll("([a-z])([A-Z])", "$1 $2");
        words = words.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private NotificationCompat.Builder makeBaseBuilder() {
        return new NotificationCompat.Builder(getApplicationContext(), CHANNEL_GITLAB_NOTIFICATIONS)
                .setSmallIcon(R.drawable.notification)
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.octodroid));
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
            case "commented":           return "commented";
            default: return actionName.replace('_', ' ');
        }
    }

    /** Builds a one-line notification message: "Jay mentioned you in #34". */
    private static String formatTodoMessage(Context ctx, GitLabTodo todo) {
        String authorName = todo.author != null
                ? ApiHelpers.getUserDisplayName(ctx, todo.author) : "Someone";
        String verb = formatActionVerb(todo.actionName);
        String ref = "";
        if (todo.isCommit() && todo.commitSha != null) {
            ref = " in commit " + todo.commitSha.substring(0, Math.min(8, todo.commitSha.length()));
            if (todo.commitTitle != null && !todo.commitTitle.isEmpty()) {
                ref += " " + todo.commitTitle;
            }
        } else if (todo.target != null) {
            String prefix = "MergeRequest".equals(todo.targetType) ? "!" : "#";
            ref = " in " + prefix + todo.target.iid;
        }
        return authorName + " " + verb + ref;
    }

    /** API base of the account a notification belongs to, e.g. "https://host/api/v4/". */
    static String apiBaseForLogin(String login) {
        String instanceUrl = com.gl4a.Gl4Application.get().getInstanceUrlForLogin(login);
        if (instanceUrl == null) return null;
        return instanceUrl.endsWith("/") ? instanceUrl + "api/v4/" : instanceUrl + "/api/v4/";
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
