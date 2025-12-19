package de.blinkt.openvpn.core;

import android.content.Context;
import android.content.SharedPreferences;

public final class StatusSnapshotStore {
    private static final String PREFS_NAME = "ovpn_status_snapshot";
    private static final String KEY_STATE = "state";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_RESID = "resid";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_TIMESTAMP = "timestamp_ms";

    private StatusSnapshotStore() {
    }

    public static void save(Context context, String state, String message, int resid, ConnectionStatus level, long timestampMs) {
        if (context == null || level == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_STATE, state)
                .putString(KEY_MESSAGE, message)
                .putInt(KEY_RESID, resid)
                .putString(KEY_LEVEL, level.name())
                .putLong(KEY_TIMESTAMP, timestampMs)
                .apply();
    }

    public static StatusSnapshot load(Context context) {
        if (context == null) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String levelName = prefs.getString(KEY_LEVEL, null);
        if (levelName == null) {
            return null;
        }
        ConnectionStatus level;
        try {
            level = ConnectionStatus.valueOf(levelName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String state = prefs.getString(KEY_STATE, null);
        String message = prefs.getString(KEY_MESSAGE, null);
        int resid = prefs.getInt(KEY_RESID, 0);
        long timestamp = prefs.getLong(KEY_TIMESTAMP, 0L);
        return new StatusSnapshot(state, message, resid, level, timestamp);
    }
}
