package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory ring buffer logger for debug log collection.
 * Disabled by default; enabled via Settings → Debug.
 * Logs API URLs (no tokens), HTTP status codes, and caught exceptions.
 */
public class DebugLogger {

    public static final String PREF_KEY_ENABLED = "debug_logging_enabled";
    private static final int MAX_ENTRIES = 500;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static DebugLogger sInstance;

    private final ArrayDeque<String> mBuffer = new ArrayDeque<>(MAX_ENTRIES);
    private boolean mEnabled = false;

    private DebugLogger() {}

    public static synchronized DebugLogger get() {
        if (sInstance == null) {
            sInstance = new DebugLogger();
        }
        return sInstance;
    }

    public void init(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Gh4a-pref", Context.MODE_PRIVATE);
        mEnabled = prefs.getBoolean(PREF_KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) {
            clear();
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public synchronized void log(String tag, String message) {
        if (!mEnabled) return;
        String entry = DATE_FORMAT.format(new Date()) + " [" + tag + "] " + message;
        if (mBuffer.size() >= MAX_ENTRIES) {
            mBuffer.pollFirst();
        }
        mBuffer.addLast(entry);
    }

    public void api(String method, String url, int statusCode, long responseBytes) {
        log("API", method + " " + url + " → " + statusCode
                + (responseBytes >= 0 ? " (" + responseBytes + "B)" : ""));
    }

    public void error(String tag, String message, Throwable t) {
        log(tag, message + (t != null ? ": " + t.getClass().getSimpleName()
                + " — " + t.getMessage() : ""));
    }

    public synchronized void clear() {
        mBuffer.clear();
    }

    public synchronized int size() {
        return mBuffer.size();
    }

    /**
     * Writes the buffer to a temp file and returns it for sharing.
     * Returns null if buffer is empty or write fails.
     */
    public synchronized File writeToFile(Context context) {
        if (mBuffer.isEmpty()) return null;
        try {
            File dir = new File(context.getCacheDir(), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "octolab-debug.log");
            try (FileWriter w = new FileWriter(file, false)) {
                w.write("OctoLab debug log — " + new Date() + "\n");
                w.write("Entries: " + mBuffer.size() + "/" + MAX_ENTRIES + "\n");
                w.write("----------------------------------------\n");
                for (String line : mBuffer) {
                    w.write(line);
                    w.write('\n');
                }
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
