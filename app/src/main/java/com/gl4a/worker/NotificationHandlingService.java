package com.gl4a.worker;

import android.app.IntentService;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import android.util.Log;

import com.gl4a.Gl4Application;

import java.util.Date;

/**
 * Background service for handling GitLab to-do (notification) actions.
 * The original GitHub notification mark-as-read logic has been stubbed
 * pending GitLab to-do API integration.
 */
public class NotificationHandlingService extends IntentService {
    private static final String EXTRA_REPO_OWNER = "owner";
    private static final String EXTRA_REPO_NAME = "repo";
    private static final String EXTRA_NOTIFICATION_ID = "notification_id";
    private static final String EXTRA_TIMESTAMP = "timestamp";

    private static final String ACTION_MARK_SEEN = "com.gl4a.action.MARK_AS_SEEN";
    private static final String ACTION_MARK_READ = "com.gl4a.action.MARK_AS_READ";
    private static final String ACTION_HANDLE_NOTIFICATION_DISMISS =
            "com.gl4a.action.HANDLE_NOTIFICATION_DISMISS";

    public static Intent makeMarkNotificationsSeenIntent(Context context, int notificationId) {
        // Encode the ID in the data URI so filterEquals() sees each project as a distinct
        // PendingIntent. Extras are ignored by filterEquals(), meaning FLAG_UPDATE_CURRENT
        // could silently return a cached PendingIntent without the new extras.
        return new Intent(context, NotificationHandlingService.class)
                .setAction(ACTION_MARK_SEEN)
                .setData(android.net.Uri.parse("notification://cancel/" + notificationId));
    }

    public static Intent makeHandleDismissIntent(Context context, int notificationId) {
        return new Intent(context, NotificationHandlingService.class)
                .setAction(ACTION_HANDLE_NOTIFICATION_DISMISS)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId);
    }

    public static Intent makeMarkReposNotificationsAsReadActionIntent(Context context,
            int notificationId, String repoOwner, String repoName) {
        // Encode ID in data URI so filterEquals() treats each project as a distinct
        // PendingIntent — extras are ignored by filterEquals(), causing FLAG_UPDATE_CURRENT
        // to return a cached PendingIntent without the extras (notificationId == -1).
        return new Intent(context, NotificationHandlingService.class)
                .setAction(ACTION_MARK_READ)
                .setData(android.net.Uri.parse("notification://cancel/" + notificationId))
                .putExtra(EXTRA_REPO_OWNER, repoOwner)
                .putExtra(EXTRA_REPO_NAME, repoName);
    }

    public NotificationHandlingService() {
        super("NotificationHandlingService");
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        String repoOwner = intent.getStringExtra(EXTRA_REPO_OWNER);
        String repoName = intent.getStringExtra(EXTRA_REPO_NAME);
        // Read ID from data URI (set by makeMarkNotificationsSeenIntent) or fall back to extra.
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        if (intent.getData() != null) {
            try {
                notificationId = Integer.parseInt(intent.getData().getLastPathSegment());
            } catch (NumberFormatException ignored) {}
        }

        switch (intent.getAction()) {
            case ACTION_MARK_SEEN:
                // notificationId==0 or -1 (summary/not-found) → cancelAll.
                // Any other value (including negative) is a valid per-project ID → cancel specific.
                if (notificationId != 0 && notificationId != -1) {
                    NotificationsWorker.markNotificationsAsSeen(this, false);
                    NotificationManager nm =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(notificationId);
                } else {
                    NotificationsWorker.markNotificationsAsSeen(this, true);
                }
                break;
            case ACTION_HANDLE_NOTIFICATION_DISMISS:
                if (notificationId != -1) {
                    NotificationsWorker.handleNotificationDismiss(this, notificationId);
                }
                break;
            case ACTION_MARK_READ:
                if (repoOwner != null && repoName != null) {
                    // TODO: Implement GitLab to-do mark-as-read via GitLabTodoService
                    Log.d(Gl4Application.LOG_TAG, "Mark repo todos as read: " + repoOwner + "/" + repoName);
                }
                // notificationId==0 reserved for group summary; -1 means ID not found.
                // Any other value (including negative) is a valid per-project ID.
                if (notificationId != 0 && notificationId != -1) {
                    androidx.core.app.NotificationManagerCompat nm =
                            androidx.core.app.NotificationManagerCompat.from(this);
                    NotificationsWorker.handleNotificationDismiss(this, notificationId);
                    nm.cancel(notificationId);
                    // Do NOT cancel the group summary (ID=0) here — cancelling the
                    // summary cancels ALL children on Android. The summary will clear
                    // automatically when the user opens the app (markNotificationsAsSeen
                    // in onStart calls cancelAll) or when all children are dismissed.
                }
                break;
        }
    }
}
