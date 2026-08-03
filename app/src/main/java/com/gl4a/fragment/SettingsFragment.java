package com.gl4a.fragment;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gl4a.Gl4Application;
import com.gl4a.R;
import android.net.Uri;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.preference.SwitchPreference;
import com.gl4a.utils.DebugLogger;
import com.gl4a.worker.NotificationsWorker;
import com.gl4a.widget.IntegerListPreference;
import java.io.File;

public class SettingsFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    public interface OnStateChangeListener {
        void onThemeChanged();
    }

    public static final String PREF_NAME = "Gh4a-pref";

    public static final String KEY_THEME = "theme";
    public static final String KEY_START_PAGE = "start_page";
    public static final String KEY_TEXT_SIZE = "webview_initial_zoom";
    public static final String KEY_GIF_LOADING = "http_gif_load_mode";
    public static final String KEY_CUSTOM_TABS = "use_custom_tabs";
    public static final String KEY_NOTIFICATIONS = "notifications";
    public static final String KEY_NOTIFICATION_INTERVAL = "notification_interval";
    private static final String KEY_ABOUT = "about";
    private static final String KEY_OPEN_SOURCE_COMPONENTS = "open_source_components";
    private static final String KEY_DEBUG_LOGGING = DebugLogger.PREF_KEY_ENABLED;
    private static final String KEY_DEBUG_SHARE = "debug_share_logs";
    private static final String KEY_DEBUG_CLEAR = "debug_clear_logs";

    private static final String KEY_WORKER_STATUS_INFO = "worker_status_info";
    private static final String KEY_WORKER_LAST_SYNC   = "worker_last_sync";
    private static final String KEY_WORKER_SYNC_NOW    = "worker_sync_now";

    private OnStateChangeListener mListener;
    private IntegerListPreference mThemePref;
    private Preference mAboutPref;
    private Preference mOpenSourcePref;
    private TwoStatePreference mNotificationsPref;
    private IntegerListPreference mNotificationIntervalPref;
    private Preference mWorkerStatusPref;
    private Preference mWorkerLastSyncPref;
    private Preference mWorkerSyncNowPref;
    private SwitchPreference mDebugLoggingPref;
    private Preference mDebugSharePref;
    private Preference mDebugClearPref;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof OnStateChangeListener)) {
            throw new IllegalArgumentException("Activity must implement OnStateChangeListener");
        }
        mListener = (OnStateChangeListener) context;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(PREF_NAME);
        addPreferencesFromResource(R.xml.settings);

        mThemePref = findPreference(KEY_THEME);
        mThemePref.setOnPreferenceChangeListener(this);

        mAboutPref = findPreference(KEY_ABOUT);
        mAboutPref.setOnPreferenceClickListener(this);
        mAboutPref.setSummary(getAppName());

        mOpenSourcePref = findPreference(KEY_OPEN_SOURCE_COMPONENTS);
        mOpenSourcePref.setOnPreferenceClickListener(this);

        mNotificationsPref = findPreference(KEY_NOTIFICATIONS);
        mNotificationsPref.setOnPreferenceChangeListener(this);

        mNotificationIntervalPref = findPreference(KEY_NOTIFICATION_INTERVAL);
        mNotificationIntervalPref.setOnPreferenceChangeListener(this);

        mWorkerStatusPref  = findPreference(KEY_WORKER_STATUS_INFO);
        mWorkerLastSyncPref = findPreference(KEY_WORKER_LAST_SYNC);
        mWorkerSyncNowPref  = findPreference(KEY_WORKER_SYNC_NOW);
        mWorkerSyncNowPref.setOnPreferenceClickListener(this);
        refreshWorkerStatus();

        mDebugLoggingPref = findPreference(KEY_DEBUG_LOGGING);
        mDebugLoggingPref.setOnPreferenceChangeListener(this);

        mDebugSharePref = findPreference(KEY_DEBUG_SHARE);
        mDebugSharePref.setOnPreferenceClickListener(this);

        mDebugClearPref = findPreference(KEY_DEBUG_CLEAR);
        mDebugClearPref.setOnPreferenceClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshWorkerStatus();
    }

    private void refreshWorkerStatus() {
        if (mWorkerStatusPref == null || mWorkerLastSyncPref == null) return;
        Context ctx = getContext();
        if (ctx == null) return;

        // Query WorkManager for the periodic task state on a background thread.
        com.google.common.util.concurrent.ListenableFuture<java.util.List<androidx.work.WorkInfo>> future =
                androidx.work.WorkManager.getInstance(ctx)
                        .getWorkInfosByTag(NotificationsWorker.WORK_TAG);
        future.addListener(() -> {
            if (!isAdded()) return;
            try {
                java.util.List<androidx.work.WorkInfo> infos = future.get();
                androidx.work.WorkInfo.State state = null;
                for (androidx.work.WorkInfo info : infos) {
                    androidx.work.WorkInfo.State s = info.getState();
                    if (state == null || s == androidx.work.WorkInfo.State.RUNNING) state = s;
                }
                final String stateSummary;
                if (state == androidx.work.WorkInfo.State.RUNNING
                        || state == androidx.work.WorkInfo.State.ENQUEUED) {
                    stateSummary = getString(R.string.worker_state_running);
                } else if (state == null) {
                    stateSummary = getString(R.string.worker_state_stopped);
                } else {
                    stateSummary = getString(R.string.worker_state_unknown);
                }
                long lastCheckMs = NotificationsWorker.getLastCheckMillis(ctx);
                final String lastSyncSummary;
                if (lastCheckMs == 0) {
                    lastSyncSummary = getString(R.string.worker_last_sync_never);
                } else {
                    lastSyncSummary = com.gl4a.utils.StringUtils.formatRelativeTime(
                            ctx, new java.util.Date(lastCheckMs), true);
                }
                requireActivity().runOnUiThread(() -> {
                    mWorkerStatusPref.setSummary(stateSummary);
                    mWorkerLastSyncPref.setSummary(lastSyncSummary);
                });
            } catch (Exception ignored) {}
        }, ctx.getMainExecutor());
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref == mThemePref) {
            mListener.onThemeChanged();
            return true;
        }
        if (pref == mNotificationsPref) {
            if ((boolean) newValue) {
                NotificationsWorker.createNotificationChannels(getActivity());
                NotificationsWorker.schedule(getContext(),
                        Integer.valueOf(mNotificationIntervalPref.getValue()));
                // On Android 13 and up, notification permissions must be granted manually
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        getActivity().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    getActivity().requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
                }
            } else {
                NotificationsWorker.cancel(getContext());
            }
            return true;
        }
        if (pref == mNotificationIntervalPref) {
            if (mNotificationsPref.isChecked()) {
                NotificationsWorker.schedule(getContext(), Integer.parseInt((String) newValue));
            }
            return true;
        }
        if (pref == mDebugLoggingPref) {
            DebugLogger.get().setEnabled((boolean) newValue);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference pref) {
        if (pref == mWorkerSyncNowPref) {
            NotificationsWorker.runNow(requireContext());
            Toast.makeText(getContext(), R.string.worker_sync_now_toast, Toast.LENGTH_SHORT).show();
            // Refresh status after a short delay to pick up the enqueued state.
            requireView().postDelayed(this::refreshWorkerStatus, 600);
            return true;
        } else if (pref == mAboutPref) {
            boolean loggedIn = Gl4Application.get().isAuthorized();
            AboutDialogFragment.newInstance(getAppName(), loggedIn)
                    .show(getChildFragmentManager(), "about");
            return true;
        } else if (pref == mOpenSourcePref) {
            new OpenSourceComponentListDialogFragment()
                    .show(getChildFragmentManager(), "opensource");
            return true;
        } else if (pref == mDebugSharePref) {
            shareLogs();
            return true;
        } else if (pref == mDebugClearPref) {
            DebugLogger.get().clear();
            Toast.makeText(getContext(), R.string.debug_logs_cleared, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void shareLogs() {
        DebugLogger logger = DebugLogger.get();
        if (logger.size() == 0) {
            Toast.makeText(getContext(), R.string.debug_no_logs, Toast.LENGTH_LONG).show();
            return;
        }
        File logFile = logger.writeToFile(requireContext());
        if (logFile == null) {
            Toast.makeText(getContext(), R.string.debug_no_logs, Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().getPackageName() + ".fileprovider",
                logFile);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "OctoLab debug log");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.debug_share_logs)));
    }

    private String getAppName() {
        String version = getAppVersion();
        return getString(R.string.app_name) + " v" + version;
    }

    private String getAppVersion() {
        try {
            PackageManager pm = getActivity().getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(getActivity().getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // shouldn't happen
            return "";
        }
    }

    public static class AboutDialogFragment extends DialogFragment {
        public static AboutDialogFragment newInstance(String title, boolean loggedIn) {
            AboutDialogFragment f = new AboutDialogFragment();
            Bundle args = new Bundle();
            args.putString("title", title);
            args.putBoolean("loggedIn", loggedIn);
            f.setArguments(args);
            return f;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String title = getArguments().getString("title");
            boolean loggedIn = getArguments().getBoolean("loggedIn");
            return new AboutDialog(getContext(), title, loggedIn);
        }
    }

    private static class AboutDialog extends AppCompatDialog implements View.OnClickListener {
        public AboutDialog(Context context, String title, boolean loggedIn) {
            super(context);

            setContentView(R.layout.about_dialog);
            setTitle(title);

            TextView tvCopyright = findViewById(R.id.copyright);
            tvCopyright.setText(R.string.copyright_notice);

            findViewById(R.id.btn_by_email).setOnClickListener(this);

            // Opens GitHub issues — available regardless of login state
            findViewById(R.id.btn_by_gh4a).setOnClickListener(this);

            findViewById(R.id.btn_gh4a).setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            Context context = getContext();
            int id = view.getId();

            if (id == R.id.btn_by_email) {
                Intent sendIntent = new Intent(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{
                        context.getString(R.string.my_email)
                });
                sendIntent.setType("message/rfc822");

                Intent chooserIntent = Intent.createChooser(sendIntent,
                        context.getString(R.string.send_email_title));
                context.startActivity(chooserIntent);
            } else if (id == R.id.btn_by_gh4a) {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(context.getString(R.string.my_web) + "/issues"));
                context.startActivity(intent);
            } else if (id == R.id.btn_gh4a) {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(context.getString(R.string.my_web)));
                context.startActivity(intent);
            }
        }
    }

    public static class OpenSourceComponentListDialogFragment extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            RecyclerView rv = (RecyclerView) inflater.inflate(R.layout.open_source_component_list, null);
            rv.setLayoutManager(new LinearLayoutManager(getContext()));
            rv.setAdapter(new OpenSourceComponentAdapter(getContext()));

            return new AlertDialog.Builder(getContext())
                    .setView(rv)
                    .setTitle(R.string.open_source_components)
                    .setPositiveButton(R.string.ok, null)
                    .create();
        }
    }

    private static class OpenSourceComponentAdapter extends RecyclerView.Adapter<OpenSourceComponentViewHolder> {
        private static final String[][] COMPONENTS = new String[][] {
            { "android-gif-drawable", "https://github.com/koral--/android-gif-drawable" },
            { "AndroidSVG", "https://github.com/BigBadaboom/androidsvg" },
            { "AndroidX", "https://github.com/androidx/androidx" },
            { "emoji-java", "https://github.com/vdurmont/emoji-java" },
            { "GitHubSdk", "https://github.com/maniac103/GitHubSdk" },
            { "HoloColorPicker", "https://github.com/LarsWerkman/HoloColorPicker" },
            { "MarkdownEdit", "https://github.com/Tunous/MarkdownEdit" },
            { "Material Design Icons", "https://github.com/google/material-design-icons" },
            { "PrettyTime", "https://github.com/ocpsoft/prettytime" },
            { "Recycler Fast Scroll", "https://github.com/pluscubed/recycler-fast-scroll" },
            { "Retrofit", "https://github.com/square/retrofit" },
            { "RxAndroid", "https://github.com/ReactiveX/RxAndroid" },
            { "RxJava", "https://github.com/ReactiveX/RxJava" },
            { "RxLoader", "https://github.com/maniac103/RxLoader" },
            { "SmoothProgressBar", "https://github.com/castorflex/SmoothProgressBar" },
        };

        private final LayoutInflater mInflater;

        public OpenSourceComponentAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public OpenSourceComponentViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int viewType) {
            View itemView = mInflater.inflate(R.layout.open_source_component_item, parent, false);
            return new OpenSourceComponentViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull OpenSourceComponentViewHolder holder, int position) {
            final String[] item = COMPONENTS[position];
            holder.bind(item[0], item[1]);
        }

        @Override
        public int getItemCount() {
            return COMPONENTS.length;
        }
    }

    private static class OpenSourceComponentViewHolder extends RecyclerView.ViewHolder {
        private final TextView mTitleView;
        private final TextView mUrlView;

        public OpenSourceComponentViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitleView = itemView.findViewById(R.id.title);
            mUrlView = itemView.findViewById(R.id.url);
        }

        public void bind(String title, String url) {
            mTitleView.setText(title);
            mUrlView.setText(url);
        }
    }
}
