package com.gl4a.worker;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.work.WorkInfo;

import java.util.List;

/**
 * Why checks stopped, in Recent runs (#325): what Android says ended the app's previous run, how
 * it ranks the app for background work and whether it saves power, and why it stopped a check.
 * Only words for the log; nothing here changes how checks run.
 */
final class RunDiagnostics {
    private static final String KEY_LAST_EXIT = "worker_last_exit_seen";

    private RunDiagnostics() {
    }

    /**
     * How the previous run of the app ended, if Android recorded it since the last look, e.g.
     * "previous run ended 11-08 15:35: low memory"; null otherwise.
     */
    @Nullable
    static String previousExit(Context ctx, SharedPreferences prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        if (am == null) return null;
        List<ApplicationExitInfo> exits;
        try {
            exits = am.getHistoricalProcessExitReasons(null, 0, 1);
        } catch (RuntimeException e) {
            return null;
        }
        if (exits == null || exits.isEmpty()) return null;
        ApplicationExitInfo exit = exits.get(0);
        if (exit.getTimestamp() <= prefs.getLong(KEY_LAST_EXIT, 0)) return null;
        prefs.edit().putLong(KEY_LAST_EXIT, exit.getTimestamp()).apply();
        String description = exit.getDescription();
        if (description != null && description.length() > 80) {
            description = description.substring(0, 80) + "…";
        }
        String reason = exitReason(exit.getReason());
        // Swiping the app away in Recent apps: Android ends it, its scheduled checks stay
        if (exit.getReason() == ApplicationExitInfo.REASON_USER_REQUESTED && description != null
                && description.toLowerCase(java.util.Locale.ROOT).contains("remove task")) {
            reason = "closed from Recent apps";
            description = null;
        }
        return "previous run ended " + time(exit.getTimestamp()) + ": " + reason
                + (description != null && !description.isEmpty() ? " (" + description + ")" : "");
    }

    private static String time(long ms) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    /**
     * Where Android's scheduler has the regular check, e.g. "running since …" or "waiting, next
     * run 14:05, 1 Android job pending" (#325): a check cut off when the app was paused or ended
     * may stay "running" and hold back the next one. Blocks briefly: call off the main thread.
     */
    static String schedulerState(Context ctx) {
        StringBuilder out = new StringBuilder();
        try {
            List<WorkInfo> infos = androidx.work.WorkManager.getInstance(ctx)
                    .getWorkInfosForUniqueWork(NotificationsWorker.WORK_TAG)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (infos == null || infos.isEmpty()) {
                out.append("regular check not scheduled");
            }
            for (WorkInfo info : infos) {
                if (out.length() > 0) out.append("; ");
                out.append("regular check ").append(info.getState().name().toLowerCase(
                        java.util.Locale.ROOT));
                if (info.getRunAttemptCount() > 0) {
                    out.append(", attempt ").append(info.getRunAttemptCount() + 1);
                }
                long next = info.getNextScheduleTimeMillis();
                if (next > 0 && next < Long.MAX_VALUE) out.append(", next run ").append(time(next));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && info.getStopReason() != WorkInfo.STOP_REASON_NOT_STOPPED) {
                    out.append(", last stopped: ").append(stopReason(info.getStopReason()));
                }
            }
        } catch (Exception e) {
            out.append("scheduler state unknown (").append(e.getClass().getSimpleName())
                    .append(')');
        }
        android.app.job.JobScheduler jobs =
                ctx.getSystemService(android.app.job.JobScheduler.class);
        if (jobs != null) {
            int pending = jobs.getAllPendingJobs().size();
            out.append("; ").append(pending).append(" Android job").append(pending == 1 ? "" : "s")
                    .append(" pending");
        }
        return out.toString();
    }

    private static String exitReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "the app ended itself";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed by the system";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "force-stopped";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "stopped by the user";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "too much battery or CPU use";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "a permission changed";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "a dependency ended";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed to start";
            case ApplicationExitInfo.REASON_OTHER: return "ended by the system";
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && reason == ApplicationExitInfo.REASON_FREEZER) {
                    return "frozen by the system";
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED) {
                    return "app updated";
                }
                return "reason " + reason;
        }
    }

    /**
     * How Android treats the app's background work now, e.g. "standby: rare, battery saver";
     * null when nothing limits it.
     */
    @Nullable
    static String limits(Context ctx) {
        StringBuilder out = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            UsageStatsManager usage = ctx.getSystemService(UsageStatsManager.class);
            if (usage != null) {
                int bucket = usage.getAppStandbyBucket();
                if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
                    out.append("standby: ").append(bucketName(bucket));
                }
            }
        }
        PowerManager power = ctx.getSystemService(PowerManager.class);
        if (power != null && power.isPowerSaveMode()) {
            if (out.length() > 0) out.append(", ");
            out.append("battery saver");
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT: return "frequent";
            case UsageStatsManager.STANDBY_BUCKET_RARE: return "rare";
            default:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
                    return "restricted";
                }
                return String.valueOf(bucket);
        }
    }

    /** Why Android stopped a running check, in words. */
    static String stopReason(int reason) {
        switch (reason) {
            case WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY: return "the network went away";
            case WorkInfo.STOP_REASON_TIMEOUT: return "it ran too long";
            case WorkInfo.STOP_REASON_QUOTA: return "background work quota used up";
            case WorkInfo.STOP_REASON_APP_STANDBY: return "app standby";
            case WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION: return "background restricted";
            case WorkInfo.STOP_REASON_DEVICE_STATE: return "the phone's state (e.g. dozing)";
            case WorkInfo.STOP_REASON_SYSTEM_PROCESSING: return "the system needed resources";
            case WorkInfo.STOP_REASON_USER: return "the user";
            case WorkInfo.STOP_REASON_PREEMPT: return "other work came first";
            case WorkInfo.STOP_REASON_CANCELLED_BY_APP: return "cancelled";
            default: return "reason " + reason;
        }
    }
}
