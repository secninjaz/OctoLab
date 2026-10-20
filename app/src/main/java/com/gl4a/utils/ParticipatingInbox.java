package com.gl4a.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.gl4a.ServiceFactory;
import com.gl4a.gitlab.model.GitLabTodo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * THE store of participating notifications (#280): comments by others, without a mention, on
 * items the user takes part in (#267), which GitLab keeps no record of. Kept per account, so they
 * can be seen again once their notification is gone, with an in-app read state for the counts on
 * the drawer's bell, the account switcher and the To-dos | Participating tabs.
 *
 * Its own database file, which backups leave out (they list what they include). At most
 * {@link #MAX_PER_ACCOUNT} per account, the oldest going first; each keeps what its notification
 * shows, the comment cut to {@link #MAX_BODY} characters (the full text is on GitLab).
 */
public final class ParticipatingInbox extends SQLiteOpenHelper {
    public static final int MAX_PER_ACCOUNT = 1000;
    private static final int MAX_BODY = 500;

    private static final String DB_NAME = "participating.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "participating";
    private static final String C_ID = "_id";
    private static final String C_LOGIN = "login";
    private static final String C_NOTE_ID = "note_id";
    private static final String C_CREATED = "created";
    private static final String C_JSON = "json";
    private static final String C_READ = "is_read";

    private static ParticipatingInbox sInstance;
    private static final Set<Runnable> sListeners = new CopyOnWriteArraySet<>();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** An entry: the notification's content, as a to-do (id = -note id), and its read state. */
    public static final class Entry {
        public final GitLabTodo comment;
        public final boolean read;

        Entry(GitLabTodo comment, boolean read) {
            this.comment = comment;
            this.read = read;
        }
    }

    public static synchronized ParticipatingInbox get(Context context) {
        if (sInstance == null) {
            sInstance = new ParticipatingInbox(context.getApplicationContext());
        }
        return sInstance;
    }

    private ParticipatingInbox(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_LOGIN + " TEXT NOT NULL, " + C_NOTE_ID + " INTEGER NOT NULL, "
                + C_CREATED + " INTEGER NOT NULL, " + C_JSON + " TEXT NOT NULL, "
                + C_READ + " INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(" + C_LOGIN + ", " + C_NOTE_ID + "))");
        db.execSQL("CREATE INDEX idx_login_created ON " + TABLE + " (" + C_LOGIN + ", "
                + C_CREATED + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /** Called on the main thread whenever counts may have changed. */
    public static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    private static void notifyChanged() {
        sMainHandler.post(() -> {
            for (Runnable listener : sListeners) {
                listener.run();
            }
        });
    }

    /**
     * Keeps a participating notification's comment (id = -note id), unread. One already kept
     * (e.g. shown again after a restart) keeps its read state.
     */
    public void add(String login, GitLabTodo comment) {
        long noteId = -comment.id;
        if (login == null || noteId <= 0) return;
        String body = comment.body;
        if (body != null && body.length() > MAX_BODY) {
            comment.body = body.substring(0, MAX_BODY) + "…";
        }
        String json;
        try {
            json = ServiceFactory.moshi().adapter(GitLabTodo.class).toJson(comment);
        } finally {
            comment.body = body;
        }
        ContentValues values = new ContentValues();
        values.put(C_LOGIN, login);
        values.put(C_NOTE_ID, noteId);
        values.put(C_CREATED, Participation.isoMillis(comment.createdAt));
        values.put(C_JSON, json);
        values.put(C_READ, 0);
        SQLiteDatabase db = getWritableDatabase();
        long row = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (row == -1) return;
        // The oldest beyond the limit go
        db.execSQL("DELETE FROM " + TABLE + " WHERE " + C_LOGIN + " = ? AND " + C_ID
                + " NOT IN (SELECT " + C_ID + " FROM " + TABLE + " WHERE " + C_LOGIN
                + " = ? ORDER BY " + C_CREATED + " DESC, " + C_ID + " DESC LIMIT "
                + MAX_PER_ACCOUNT + ")", new Object[] { login, login });
        notifyChanged();
    }

    /** An account's entries, newest first. */
    public List<Entry> list(String login) {
        List<Entry> entries = new ArrayList<>();
        com.squareup.moshi.JsonAdapter<GitLabTodo> adapter =
                ServiceFactory.moshi().adapter(GitLabTodo.class);
        try (Cursor c = getReadableDatabase().query(TABLE, new String[] { C_JSON, C_READ },
                C_LOGIN + " = ?", new String[] { login }, null, null,
                C_CREATED + " DESC, " + C_ID + " DESC")) {
            while (c.moveToNext()) {
                try {
                    GitLabTodo comment = adapter.fromJson(c.getString(0));
                    if (comment != null) entries.add(new Entry(comment, c.getInt(1) != 0));
                } catch (Exception ignored) {
                    // An entry that can't be read is left out
                }
            }
        }
        return entries;
    }

    /** Unread entries per account. */
    public Map<String, Integer> unreadCounts() {
        Map<String, Integer> counts = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + C_LOGIN + ", COUNT(*) FROM "
                + TABLE + " WHERE " + C_READ + " = 0 GROUP BY " + C_LOGIN, null)) {
            while (c.moveToNext()) {
                counts.put(c.getString(0), c.getInt(1));
            }
        }
        return counts;
    }

    public int unreadCount(@Nullable String login) {
        Integer count = login != null ? unreadCounts().get(login) : null;
        return count != null ? count : 0;
    }

    /** Unread entries of every account but {@code login}. */
    public int unreadCountOthers(@Nullable String login) {
        int total = 0;
        for (Map.Entry<String, Integer> e : unreadCounts().entrySet()) {
            if (login == null || !ApiHelpers.loginEquals(login, e.getKey())) {
                total += e.getValue();
            }
        }
        return total;
    }

    /** Marks a comment read, by its note ID (the to-do's -id). */
    public void markRead(String login, long noteId) {
        ContentValues values = new ContentValues();
        values.put(C_READ, 1);
        int changed = getWritableDatabase().update(TABLE, values, C_LOGIN + " = ? AND "
                + C_NOTE_ID + " = ? AND " + C_READ + " = 0",
                new String[] { login, String.valueOf(noteId) });
        if (changed > 0) notifyChanged();
    }

    public void markAllRead(String login) {
        ContentValues values = new ContentValues();
        values.put(C_READ, 1);
        int changed = getWritableDatabase().update(TABLE, values,
                C_LOGIN + " = ? AND " + C_READ + " = 0", new String[] { login });
        if (changed > 0) notifyChanged();
    }

    public void delete(String login, long noteId) {
        int changed = getWritableDatabase().delete(TABLE, C_LOGIN + " = ? AND " + C_NOTE_ID
                + " = ?", new String[] { login, String.valueOf(noteId) });
        if (changed > 0) notifyChanged();
    }

    /** An account removed: its entries go too. */
    public void deleteAccount(String login) {
        int changed = getWritableDatabase().delete(TABLE, C_LOGIN + " = ?",
                new String[] { login });
        if (changed > 0) notifyChanged();
    }
}
