package com.gl4a.worker;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Checks again when a network appears that may reach a server a check couldn't reach (#325),
 * as far as Android lets an app know, without timers:
 *
 * <ul>
 * <li>offline: a check Android starts when any network is back;</li>
 * <li>no VPN: one it starts when a VPN connects (servers reachable only through one);</li>
 * <li>on mobile data: one it starts when Wi-Fi connects (servers reachable only there).</li>
 * </ul>
 *
 * Each waits at no cost, even with the app closed, and runs once. Only those whose network isn't
 * there yet are set up, so none starts straight away, again and again; all are dropped once
 * every account is reached. While the app runs, a change of the phone's network also checks
 * again ({@link #onDefaultNetworkChanged}), which Android doesn't tell closed apps.
 */
public final class NetworkTriggers {
    static final String KEY_TRIGGER = "trigger";
    private static final String WORK_BACK_ONLINE = "octolab_check_back_online";
    private static final String WORK_VPN = "octolab_check_vpn";
    private static final String WORK_WIFI = "octolab_check_wifi";
    private static final String WORK_CHANGED = "octolab_check_network_changed";

    private NetworkTriggers() {
    }

    /** After a check couldn't reach a server because of the network. */
    static void arm(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkCapabilities current = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (current == null
                || !current.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            enqueue(context, WORK_BACK_ONLINE, "back online", new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build());
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        if (!hasVpn(cm)) {
            enqueue(context, WORK_VPN, "VPN connected", new Constraints.Builder()
                    .setRequiredNetworkRequest(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            .build(), NetworkType.CONNECTED)
                    .build());
        }
        if (!current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !current.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            enqueue(context, WORK_WIFI, "Wi-Fi connected", new Constraints.Builder()
                    .setRequiredNetworkRequest(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(), NetworkType.CONNECTED)
                    .build());
        }
    }

    /**
     * Every account was reached: nothing to wait for. The waiter that started this check, if
     * one did, has run and isn't cancelled from inside itself.
     */
    static void disarm(Context context, @Nullable String runningTrigger) {
        WorkManager wm = WorkManager.getInstance(context);
        String[][] waiters = {
                { WORK_BACK_ONLINE, "back online" },
                { WORK_VPN, "VPN connected" },
                { WORK_WIFI, "Wi-Fi connected" },
        };
        for (String[] waiter : waiters) {
            if (!waiter[1].equals(runningTrigger)) wm.cancelUniqueWork(waiter[0]);
        }
    }

    /** Checks were turned off: no waiter, no pending check after a network change. */
    static void cancelAll(Context context) {
        disarm(context, null);
        WorkManager.getInstance(context).cancelUniqueWork(WORK_CHANGED);
    }

    private static boolean hasVpn(ConnectivityManager cm) {
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private static void enqueue(Context context, String name, String trigger,
            Constraints constraints) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NotificationsWorker.class)
                .setConstraints(constraints)
                .setInputData(new Data.Builder().putString(KEY_TRIGGER, trigger).build())
                .build();
        // KEEP: one waiter of each kind, set up again only after it ran
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request);
    }

    /**
     * While the app runs: the phone moved to another network (Wi-Fi, mobile data, a VPN). If the
     * last check couldn't reach a server, check again once the network has settled; several
     * changes in a row make one check.
     */
    public static void watchWhileRunning(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        Context app = context.getApplicationContext();
        final Network[] last = { cm.getActiveNetwork() };
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // Android calls this at once for the network already in use: not a change
                if (network.equals(last[0])) return;
                last[0] = network;
                onDefaultNetworkChanged(app);
            }
        });
    }

    private static void onDefaultNetworkChanged(Context context) {
        if (!NotificationsWorker.lastCheckMissedServer(context)) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NotificationsWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setInputData(new Data.Builder()
                        .putString(KEY_TRIGGER, "network changed").build())
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_CHANGED, ExistingWorkPolicy.REPLACE, request);
    }

    /** What started a check, for Recent runs; null for the regular one. */
    @Nullable
    static String triggerOf(Data input) {
        return input.getString(KEY_TRIGGER);
    }
}
