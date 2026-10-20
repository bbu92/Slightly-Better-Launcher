package com.bbu.sbl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Choices made in the launcher's own settings menu. They sit on top of config.xml and a theme's
 * config.json (see {@link Config#load}); a key that was never set here leaves the lower layers alone.
 */
final class Prefs {
    static final String COLUMNS = "columns";
    static final String ROWS_BELOW_DOCK = "rows_below_dock";
    static final String SORT_MODE = "sort_mode";
    static final String DOCK_APPS = "dock_apps";
    static final String HIDDEN_APPS = "hidden_apps";
    static final String MANUAL_ORDER = "manual_order";
    static final String INPUTS_ROW = "show_inputs_row";
    static final String FEATURED = "show_featured";
    static final String FEATURED_VIDEO = "featured_video";
    /** Seconds between hero items; 0 turns the automatic turning off. */
    static final String FEATURED_INTERVAL = "featured_interval";
    static final String WEATHER = "show_weather";
    /** "c" or "f". */
    static final String WEATHER_UNIT = "weather_unit";
    static final String MODULE_CARDS = "module_cards";
    static final String THEME = "theme_name";
    /** "dark" or "light"; see {@link UiMode}. */
    static final String UI_MODE = "ui_mode";
    /** A language tag from {@link Lang#TAGS}, or "auto" (the TV's language when translated, else English). */
    static final String LANGUAGE = "language";
    private static final String VERSION = "version";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("sbl_prefs", Context.MODE_PRIVATE);
    }

    static boolean has(Context c, String key) {
        return sp(c).contains(key);
    }

    static int getInt(Context c, String key, int fallback) {
        return sp(c).getInt(key, fallback);
    }

    static boolean getBool(Context c, String key, boolean fallback) {
        return sp(c).getBoolean(key, fallback);
    }

    static String getString(Context c, String key, String fallback) {
        return sp(c).getString(key, fallback);
    }

    static List<String> getList(Context c, String key, List<String> fallback) {
        String raw = sp(c).getString(key, null);
        if (raw == null) return fallback;
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            return fallback;
        }
        return out;
    }

    static void putInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
        touch(c);
    }

    static void putBool(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
        touch(c);
    }

    static void putString(Context c, String key, String v) {
        sp(c).edit().putString(key, v).apply();
        touch(c);
    }

    static void putList(Context c, String key, List<String> v) {
        sp(c).edit().putString(key, new JSONArray(v).toString()).apply();
        touch(c);
    }

    static void remove(Context c, String... keys) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : keys) e.remove(k);
        e.apply();
        touch(c);
    }

    /** Preview clips play on their own, except on low-memory TVs (there is no menu setting for it). */
    static boolean featuredVideo(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        return sp(c).getBoolean(FEATURED_VIDEO, !lowRam);
    }

    /** Back to config.xml and the theme: forgets every menu choice except the usage statistics. */
    static void resetAll(Context c) {
        remove(c, COLUMNS, ROWS_BELOW_DOCK, SORT_MODE, DOCK_APPS, HIDDEN_APPS, MANUAL_ORDER, INPUTS_ROW,
                FEATURED, FEATURED_VIDEO, FEATURED_INTERVAL, WEATHER, WEATHER_UNIT, MODULE_CARDS, UI_MODE, LANGUAGE);
    }

    /** Every menu choice, for the backup. The change counter and the active theme's name are not part of it. */
    static java.util.Map<String, ?> all(Context c) {
        java.util.Map<String, Object> out = new java.util.HashMap<>(sp(c).getAll());
        out.remove(VERSION);
        out.remove(THEME);
        return out;
    }

    /** Replaces every menu choice with the given values (a restored backup); other keys are dropped. */
    static void replaceAll(Context c, java.util.Map<String, Object> values) {
        SharedPreferences p = sp(c);
        int next = p.getInt(VERSION, 0) + 1;
        String theme = p.getString(THEME, null);
        SharedPreferences.Editor e = p.edit().clear();
        for (java.util.Map.Entry<String, Object> v : values.entrySet()) {
            Object o = v.getValue();
            if (v.getKey().equals(VERSION) || v.getKey().equals(THEME)) continue;
            if (o instanceof Boolean) e.putBoolean(v.getKey(), (Boolean) o);
            else if (o instanceof Integer) e.putInt(v.getKey(), (Integer) o);
            else if (o instanceof Long) e.putLong(v.getKey(), (Long) o);
            else if (o instanceof Float) e.putFloat(v.getKey(), (Float) o);
            else if (o instanceof String) e.putString(v.getKey(), (String) o);
        }
        if (theme != null) e.putString(THEME, theme);
        e.putInt(VERSION, next);
        e.apply();
    }

    /** Bumped on every change so the home screen can tell it has to rebuild itself. */
    static int version(Context c) {
        return sp(c).getInt(VERSION, 0);
    }

    private static void touch(Context c) {
        SharedPreferences p = sp(c);
        p.edit().putInt(VERSION, p.getInt(VERSION, 0) + 1).apply();
    }
}
