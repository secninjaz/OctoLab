package com.gl4a.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

/**
 * Keystore-backed encrypted storage for GitLab access tokens. Kept in its own
 * SharedPreferences file, separate from the rest of the app's settings, so it can be
 * excluded from Android Auto Backup / device transfer (see data_extraction_rules.xml)
 * without losing theme/account-list prefs on restore.
 */
public class SecureTokenStore {

    private static final String FILE_NAME = "Gl4a-secure-pref";
    // This class's own original name, used by every build before the Gh4a->Gl4a naming
    // cleanup — including test builds already installed and logged into during that
    // cleanup's own testing cycle. Not just a hypothetical "pre-release" name.
    private static final String LEGACY_FILE_NAME = "Gh4a-secure-pref";
    private static final String KEY_PREFIX_TOKEN = "token_";
    private static final String KEY_PREFIX_TOKEN_TYPE = "token_type_";

    private final SharedPreferences mPrefs;

    public SecureTokenStore(Context context) {
        SharedPreferences prefs;
        MasterKey masterKey = null;
        try {
            masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // Keystore unavailable (corrupted keystore, OEM bug) — fall back to a plain
            // file rather than crashing on every launch. Still isolated from the main
            // prefs file and excluded from backup either way.
            prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }
        mPrefs = prefs;
        migrateLegacyFileIfNeeded(context, masterKey);
    }

    /**
     * One-time migration for installs upgrading from before this class was renamed
     * from Gh4a-secure-pref to Gl4a-secure-pref. Opens the old file with the same
     * Keystore master key (a MasterKey isn't tied to one specific file) and moves
     * every entry across.
     */
    private void migrateLegacyFileIfNeeded(Context context, MasterKey masterKey) {
        if (masterKey == null || !mPrefs.getAll().isEmpty()) return;
        try {
            SharedPreferences legacyPrefs = EncryptedSharedPreferences.create(
                    context,
                    LEGACY_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            Map<String, ?> all = legacyPrefs.getAll();
            if (all.isEmpty()) return;
            SharedPreferences.Editor editor = mPrefs.edit();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getValue() instanceof String) {
                    editor.putString(entry.getKey(), (String) entry.getValue());
                }
            }
            editor.apply();
            legacyPrefs.edit().clear().apply();
        } catch (GeneralSecurityException | IOException e) {
            // Legacy file inaccessible/corrupt — nothing to migrate; user re-logs in.
        }
    }

    public String getToken(String login) {
        return login != null ? mPrefs.getString(KEY_PREFIX_TOKEN + login, null) : null;
    }

    public String getTokenType(String login) {
        return login != null ? mPrefs.getString(KEY_PREFIX_TOKEN_TYPE + login, null) : null;
    }

    public void putToken(String login, String token, String tokenType) {
        mPrefs.edit()
                .putString(KEY_PREFIX_TOKEN + login, token)
                .putString(KEY_PREFIX_TOKEN_TYPE + login, tokenType)
                .apply();
    }

    public void removeToken(String login) {
        mPrefs.edit()
                .remove(KEY_PREFIX_TOKEN + login)
                .remove(KEY_PREFIX_TOKEN_TYPE + login)
                .apply();
    }

    /**
     * One-time migration for installs upgrading from a version that stored tokens in
     * plaintext inside the main (backed-up) prefs file. Moves the token into the
     * encrypted store and scrubs it from the legacy location.
     */
    public void migrateIfNeeded(SharedPreferences legacyPrefs, String login) {
        if (login == null || mPrefs.contains(KEY_PREFIX_TOKEN + login)) return;
        String legacyKey = KEY_PREFIX_TOKEN + login;
        String legacyTypeKey = KEY_PREFIX_TOKEN_TYPE + login;
        String token = legacyPrefs.getString(legacyKey, null);
        if (token == null) return;
        String type = legacyPrefs.getString(legacyTypeKey, null);
        putToken(login, token, type);
        legacyPrefs.edit().remove(legacyKey).remove(legacyTypeKey).apply();
    }
}
