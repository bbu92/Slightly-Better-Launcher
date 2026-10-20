package com.bbu.sbl;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Themes as plain folders: a folder with a {@code config.json} (see themes/README.md) and an optional
 * wallpaper picture. They are looked for in a {@code TVThemes} or {@code Themes} folder on the TV's
 * storage and on every USB drive, and in the launcher's own files folder, so a theme can be dropped on
 * a USB stick and picked from Launcher settings. Applying one copies it into the folder {@link Config}
 * already reads its overlay from, so nothing else in the launcher knows where a theme came from.
 */
final class ThemeManager {
    static final class Theme {
        final String name;
        final File dir;

        Theme(String name, File dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    static final String[] WALLPAPER_NAMES = {"wallpaper.jpg", "wallpaper.jpeg", "wallpaper.png", "wallpaper.webp"};
    private static final String[] FOLDER_NAMES = {"TVThemes", "Themes"};
    private static final long MAX_WALLPAPER = 8L * 1024 * 1024;
    private static final long MAX_CONFIG = 64L * 1024;
    /** Settings a theme can carry that the menu can also set; applying a theme hands them back to the theme. */
    private static final String[] MENU_KEYS = {
            Prefs.COLUMNS, Prefs.ROWS_BELOW_DOCK, Prefs.SORT_MODE, Prefs.DOCK_APPS, Prefs.HIDDEN_APPS,
            Prefs.INPUTS_ROW, Prefs.FEATURED, Prefs.MODULE_CARDS};

    private ThemeManager() {
    }

    /** The folders searched for themes, for showing to the user. */
    static List<File> roots(Context ctx) {
        List<File> roots = new ArrayList<>();
        File own = ctx.getExternalFilesDir("themes");
        if (own != null) roots.add(own);
        List<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        File[] mounted = new File("/storage").listFiles();
        if (mounted != null) {
            for (File v : mounted) {
                String n = v.getName();
                if (!n.equals("self") && !n.equals("emulated") && v.isDirectory()) volumes.add(v);
            }
        }
        for (File v : volumes) {
            for (String f : FOLDER_NAMES) roots.add(new File(v, f));
        }
        return roots;
    }

    /** Every theme found, by name. A root that itself holds a config.json is one theme. */
    static List<Theme> scan(Context ctx) {
        Map<String, Theme> found = new LinkedHashMap<>();
        for (File root : roots(ctx)) {
            try {
                if (!root.isDirectory()) continue;
                if (new File(root, Config.FILE_NAME).isFile()) add(found, new Theme(root.getName(), root));
                File[] kids = root.listFiles();
                if (kids == null) continue;
                Arrays.sort(kids);
                for (File k : kids) {
                    if (k.isDirectory() && new File(k, Config.FILE_NAME).isFile()) add(found, new Theme(k.getName(), k));
                }
            } catch (SecurityException ignored) {
                // A drive we may not read: skip it.
            }
        }
        List<Theme> out = new ArrayList<>(found.values());
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void add(Map<String, Theme> found, Theme t) {
        if (!found.containsKey(t.name)) found.put(t.name, t);
    }

    /** Applies a theme; returns null on success or a short reason on failure. */
    static String apply(Context ctx, Theme theme) {
        File dest = Config.dir(ctx);
        if (dest == null) return "storage";
        File cfg = new File(theme.dir, Config.FILE_NAME);
        JSONObject json;
        try {
            if (cfg.length() > MAX_CONFIG) return "config.json is larger than 64 KB";
            byte[] data = readAll(new FileInputStream(cfg), MAX_CONFIG);
            String text = new String(data, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            json = new JSONObject(text);
            writeAll(new File(dest, Config.FILE_NAME), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "config.json: " + e.getMessage();
        }
        File wallpaper = null;
        for (String n : WALLPAPER_NAMES) {
            File w = new File(theme.dir, n);
            if (w.isFile() && w.length() > 0 && w.length() <= MAX_WALLPAPER) {
                wallpaper = w;
                break;
            }
        }
        File target = new File(dest, Config.WALLPAPER_NAME);
        try {
            if (wallpaper != null) writeAll(target, readAll(new FileInputStream(wallpaper), MAX_WALLPAPER));
            else if (target.exists()) target.delete();
        } catch (Exception e) {
            return "wallpaper: " + e.getMessage();
        }
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            for (String menuKey : MENU_KEYS) {
                if (menuKey.equals(key)) Prefs.remove(ctx, key);
            }
        }
        Prefs.putString(ctx, Prefs.THEME, theme.name);
        return null;
    }

    // ---- the TV's own library of saved themes ------------------------------------------------------

    private static final long MAX_ZIP = 14L * 1024 * 1024;

    private static File libraryDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "themes");
        d.mkdirs();
        return d;
    }

    /** Themes saved in the TV's memory (the launcher's private storage), by name. */
    static List<Theme> installed(Context ctx) {
        List<Theme> out = new ArrayList<>();
        File[] kids = libraryDir(ctx).listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory() && new File(k, Config.FILE_NAME).isFile()) out.add(new Theme(k.getName(), k));
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static void delete(Context ctx, Theme theme) {
        File[] files = theme.dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        theme.dir.delete();
        if (theme.name.equals(Prefs.getString(ctx, Prefs.THEME, null))) reset(ctx);
    }

    /** Saves a theme found on the TV's storage or a USB drive into the TV's memory. Null on success, else the reason. */
    static String importTheme(Context ctx, Theme src) {
        try {
            byte[] cfg = readAll(new FileInputStream(new File(src.dir, Config.FILE_NAME)), MAX_CONFIG);
            byte[] wall = null;
            String wallName = null;
            for (String n : WALLPAPER_NAMES) {
                File w = new File(src.dir, n);
                if (w.isFile() && w.length() > 0 && w.length() <= MAX_WALLPAPER) {
                    wall = readAll(new FileInputStream(w), MAX_WALLPAPER);
                    wallName = n;
                    break;
                }
            }
            return save(ctx, src.name, cfg, wall, wallName);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Downloads a theme from an address and saves it into the TV's memory: a .zip with config.json and an
     * optional wallpaper picture, or a config.json (whose optional "wallpaper_url" names the picture).
     * Blocking: call from a background thread. Null on success, else the reason.
     */
    static String importUrl(Context ctx, String address) {
        try {
            String lower = address.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "http(s)://";
            byte[] data = download(address, MAX_ZIP);
            String name = null;
            byte[] cfg = null, wall = null;
            String wallName = null;
            if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
                try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
                    ZipEntry e;
                    int count = 0;
                    while ((e = zin.getNextEntry()) != null && count++ < 40) {
                        if (e.isDirectory()) continue;
                        String base = e.getName().substring(e.getName().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                        if (base.equals(Config.FILE_NAME) && cfg == null) {
                            cfg = readLimited(zin, MAX_CONFIG);
                        } else if (wall == null && Arrays.asList(WALLPAPER_NAMES).contains(base)) {
                            wall = readLimited(zin, MAX_WALLPAPER);
                            wallName = base;
                        }
                    }
                }
                if (cfg == null) return "config.json not found in the zip";
            } else {
                if (data.length > MAX_CONFIG) return "config.json is larger than 64 KB";
                cfg = data;
            }
            String text = new String(cfg, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JSONObject json = new JSONObject(text);
            name = json.optString("name", "").trim();
            String wallUrl = json.optString("wallpaper_url", "").trim();
            if (wall == null && wallUrl.toLowerCase(Locale.ROOT).startsWith("http")) {
                wall = download(wallUrl, MAX_WALLPAPER);
                String ext = wallUrl.toLowerCase(Locale.ROOT).replaceAll("[?#].*$", "");
                wallName = ext.endsWith(".png") ? "wallpaper.png" : ext.endsWith(".webp") ? "wallpaper.webp" : "wallpaper.jpg";
            }
            if (name.isEmpty()) {
                String path = Uri.parse(address).getLastPathSegment();
                name = path == null ? "" : path.replaceAll("\\.[A-Za-z0-9]+$", "");
            }
            return save(ctx, name, text.getBytes(StandardCharsets.UTF_8), wall, wallName);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String save(Context ctx, String rawName, byte[] cfg, byte[] wall, String wallName) throws Exception {
        new JSONObject(new String(cfg, StandardCharsets.UTF_8).replace("\uFEFF", ""));   // must parse
        String name = rawName == null ? "" : rawName.replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
        if (name.length() > 40) name = name.substring(0, 40).trim();
        if (name.isEmpty()) name = "theme";
        File dir = new File(libraryDir(ctx), name);
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        writeAll(new File(dir, Config.FILE_NAME), cfg);
        if (wall != null && wallName != null) writeAll(new File(dir, wallName), wall);
        return null;
    }

    private static byte[] download(String address, long max) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            return readAll(conn.getInputStream(), max);
        } finally {
            conn.disconnect();
        }
    }

    /** Back to the built-in look (config.xml). */
    static void reset(Context ctx) {
        File dest = Config.dir(ctx);
        if (dest != null) {
            new File(dest, Config.FILE_NAME).delete();
            new File(dest, Config.WALLPAPER_NAME).delete();
        }
        Prefs.remove(ctx, Prefs.THEME);
    }

    private static byte[] readAll(InputStream in, long max) throws Exception {
        try (InputStream s = in) {
            return readLimited(s, max);
        }
    }

    /** Reads a stream to its end without closing it (a zip entry must leave the archive stream open). */
    private static byte[] readLimited(InputStream s, long max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        long total = 0;
        while ((n = s.read(buf)) > 0) {
            total += n;
            if (total > max) throw new Exception("file too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }
}
