package com.bbu.sbl;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * How often and how recently each app was opened, for the "Recently used" and "Most used" sort
 * modes. Counted when a tile or a Watch Next item is opened from this launcher. Stored on the device only.
 */
final class UsageTracker {
    private static final String FILE = "sbl_usage";
    /** An app that stays in front is not a new launch: ignore repeats inside this window. */
    private static final long DEBOUNCE_MS = 5_000L;

    /** Everything stored, for the backup: {@code t:<pkg>} and {@code n:<pkg>} keys. */
    static Map<String, ?> all(Context c) {
        return sp(c).getAll();
    }

    /** Replaces all stored usage with the given values (a restored backup). */
    static void replaceAll(Context c, Map<String, Object> values) {
        SharedPreferences.Editor e = sp(c).edit().clear();
        for (Map.Entry<String, Object> v : values.entrySet()) {
            if (v.getKey().startsWith("t:") && v.getValue() instanceof Number) e.putLong(v.getKey(), ((Number) v.getValue()).longValue());
            else if (v.getKey().startsWith("n:") && v.getValue() instanceof Number) e.putInt(v.getKey(), ((Number) v.getValue()).intValue());
        }
        e.apply();
    }

    private UsageTracker() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized void record(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        SharedPreferences p = sp(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("t:" + pkg, 0L) < DEBOUNCE_MS) return;
        p.edit().putLong("t:" + pkg, now).putInt("n:" + pkg, p.getInt("n:" + pkg, 0) + 1).apply();
    }

    static long lastUsed(Map<String, ?> all, String pkg) {
        Object v = all.get("t:" + pkg);
        return v instanceof Long ? (Long) v : 0L;
    }

    static int count(Map<String, ?> all, String pkg) {
        Object v = all.get("n:" + pkg);
        return v instanceof Integer ? (Integer) v : 0;
    }

    static Map<String, ?> snapshot(Context c) {
        return sp(c).getAll();
    }

    static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}
