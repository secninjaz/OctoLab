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
 * Ring buffer logger for debug log collection.
 * Disabled by default; enabled via Settings → Debug.
 * Logs API URLs (no tokens), HTTP status codes, and caught exceptions.
 * Kept in the app's private storage too, so it survives Android stopping the app between
 * background checks and a restart of the phone (#277); deleted when logging is turned off.
 */
public class DebugLogger {

    public static final String PREF_KEY_ENABLED = "debug_logging_enabled";
    private static final int MAX_ENTRIES = 500;
    // With the date: the log now spans app restarts and days (#277)
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final String FILE_NAME = "debug-log.txt";

    private static DebugLogger sInstance;

    private final ArrayDeque<String> mBuffer = new ArrayDeque<>(MAX_ENTRIES);
    private boolean mEnabled = false;
    private File mFile;
    /** Lines in the file; it's rewritten from the buffer once it holds twice the entries. */
    private int mFileLines;

    private DebugLogger() {}

    public static synchronized DebugLogger get() {
        if (sInstance == null) {
            sInstance = new DebugLogger();
        }
        return sInstance;
    }

    public synchronized void init(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Gl4a-pref", Context.MODE_PRIVATE);
        mEnabled = prefs.getBoolean(PREF_KEY_ENABLED, false);
        mFile = new File(context.getFilesDir(), FILE_NAME);
        if (!mEnabled) {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
            return;
        }
        // What was logged before the app was last stopped
        mBuffer.clear();
        mFileLines = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(mFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (mBuffer.size() >= MAX_ENTRIES) mBuffer.pollFirst();
                mBuffer.addLast(line);
                mFileLines++;
            }
        } catch (IOException ignored) {
            // No log kept yet
        }
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
        if (mFile == null) return;
        try {
            if (mFileLines >= 2 * MAX_ENTRIES) {
                try (FileWriter w = new FileWriter(mFile, false)) {
                    for (String line : mBuffer) {
                        w.write(line);
                        w.write('\n');
                    }
                }
                mFileLines = mBuffer.size();
            } else {
                try (FileWriter w = new FileWriter(mFile, true)) {
                    w.write(entry);
                    w.write('\n');
                }
                mFileLines++;
            }
        } catch (IOException ignored) {
            // Kept in memory still
        }
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
        mFileLines = 0;
        if (mFile != null) {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }
    }

    public synchronized int size() {
        return mBuffer.size();
    }

    /** The log's entries, one per line. */
    public synchronized String getText() {
        StringBuilder text = new StringBuilder();
        for (String line : mBuffer) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }

    /**
     * Writes the buffer to a temp file and returns it for sharing.
     * Returns null if buffer is empty or write fails.
     */
    public synchronized File writeToFile(Context context) {
        return writeToFile(context, getText());
    }

    /**
     * Writes the log as given, e.g. redacted by {@link LogRedactor} (#278), to a temp file for
     * sharing. Returns null if there's nothing to write or writing fails.
     */
    public synchronized File writeToFile(Context context, String text) {
        if (mBuffer.isEmpty() || text == null || text.isEmpty()) return null;
        try {
            File dir = new File(context.getCacheDir(), "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "octolab-debug.log");
            try (FileWriter w = new FileWriter(file, false)) {
                w.write("OctoLab debug log — " + new Date() + "\n");
                w.write("Entries: " + mBuffer.size() + "/" + MAX_ENTRIES + "\n");
                w.write("----------------------------------------\n");
                w.write(text);
                w.write('\n');
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
