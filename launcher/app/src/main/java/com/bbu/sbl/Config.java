package com.bbu.sbl;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.bbu.sbl/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * the settings menu installs a theme folder there (see {@link ThemeManager}).
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "Sbl.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final int rowsBelowDock;
    final boolean moduleCards;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** alpha, recent, most or manual; see {@link AppAdapter}. */
    final String sortMode;
    /** Package order used by the manual sort mode. */
    final List<String> manualOrder;
    /** The row of connected inputs under the dock. */
    final boolean showInputsRow;
    /** The continue-watching shelf above the focused dock app. */
    final boolean showFeatured;
    /** Colour of the band behind the dock. */
    final int dockColor;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock,
                   boolean moduleCards, String vpnApp, boolean showHomeSettingsShortcut, int backgroundColor,
                   String sortMode, List<String> manualOrder, boolean showInputsRow, boolean showFeatured, int dockColor,
                   File wallpaper, boolean overlayApplied) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.moduleCards = moduleCards;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.sortMode = sortMode;
        this.manualOrder = Collections.unmodifiableList(manualOrder);
        this.showInputsRow = showInputsRow;
        this.showFeatured = showFeatured;
        this.dockColor = dockColor;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = UiMode.light(ctx) ? 0xFFF5F5F7 : 0xFF000000;
        String sortMode = "alpha";
        List<String> manualOrder = new ArrayList<>();
        boolean inputsRow = true, featured = true;
        int dockColor = UiMode.light(ctx) ? 0xFFFFFFFF : 0xFF141416;
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 4, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            moduleCards = bool(json, "module_cards", moduleCards);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
            sortMode = sortMode(string(json, "sort_mode", sortMode), sortMode);
            inputsRow = bool(json, "show_inputs_row", inputsRow);
            featured = bool(json, "show_featured", featured);
            dockColor = color(json, "dock_color", dockColor);
        }
        // Choices made in the launcher's own settings menu win over config.xml and the theme.
        if (Prefs.has(ctx, Prefs.DOCK_APPS)) dock = Prefs.getList(ctx, Prefs.DOCK_APPS, dock);
        if (Prefs.has(ctx, Prefs.HIDDEN_APPS)) hidden = Prefs.getList(ctx, Prefs.HIDDEN_APPS, hidden);
        if (Prefs.has(ctx, Prefs.COLUMNS)) columns = Prefs.getInt(ctx, Prefs.COLUMNS, columns);
        if (Prefs.has(ctx, Prefs.ROWS_BELOW_DOCK)) rowsBelow = Prefs.getInt(ctx, Prefs.ROWS_BELOW_DOCK, rowsBelow);
        if (Prefs.has(ctx, Prefs.MODULE_CARDS)) moduleCards = Prefs.getBool(ctx, Prefs.MODULE_CARDS, moduleCards);
        if (Prefs.has(ctx, Prefs.INPUTS_ROW)) inputsRow = Prefs.getBool(ctx, Prefs.INPUTS_ROW, inputsRow);
        if (Prefs.has(ctx, Prefs.FEATURED)) featured = Prefs.getBool(ctx, Prefs.FEATURED, featured);
        if (Prefs.has(ctx, Prefs.SORT_MODE)) sortMode = sortMode(Prefs.getString(ctx, Prefs.SORT_MODE, sortMode), sortMode);
        manualOrder = Prefs.getList(ctx, Prefs.MANUAL_ORDER, manualOrder);
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(4, Math.min(8, columns)), Math.max(0, rowsBelow), moduleCards, vpn, homeSettings, background,
                sortMode, manualOrder, inputsRow, featured, dockColor,
                wallpaper, applied);
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    static final String[] SORT_MODES = {"alpha", "recent", "most", "manual"};

    private static String sortMode(String value, String fallback) {
        for (String m : SORT_MODES) if (m.equals(value)) return m;
        return fallback;
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            // The page background is always opaque (the window behind is black anyway); other colours keep their alpha.
            return key.equals("background_color") || ((String) v).trim().length() <= 7 ? c | 0xFF000000 : c;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
