package com.bbu.sbl;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backup and restore of the launcher's own choices: every menu setting, the favourites, the manual app order,
 * hidden apps and the usage counts behind "Recently used" and "Most used". One small JSON file, written to
 * a {@code SlightlyBetterLauncher} folder on the TV's storage and on every USB drive that can be written to (and, as a last
 * resort, the launcher's own files folder, which an uninstall wipes). Themes are not in it: they have their
 * own saved copies. Backups made by earlier builds (a {@code TVLauncher} folder, {@code tvhome-backup} files)
 * are still found for restoring.
 */
final class Backup {
    static final String FOLDER = "SlightlyBetterLauncher";
    private static final String LEGACY_FOLDER = "TVLauncher";
    private static final String PREFIX = "sbl-backup";
    private static final String LEGACY_PREFIX = "tvhome-backup";
    private static final int FORMAT = 1;
    private static final int KEEP = 5;
    private static final long MAX_BYTES = 512 * 1024;

    private Backup() {
    }

    /** Folders backups are written to and looked for in. */
    static List<File> folders(Context ctx) {
        List<File> out = new ArrayList<>();
        out.add(new File(Environment.getExternalStorageDirectory(), FOLDER));
        File[] mounted = new File("/storage").listFiles();
        if (mounted != null) {
            for (File v : mounted) {
                String n = v.getName();
                if (!n.equals("self") && !n.equals("emulated") && v.isDirectory()) out.add(new File(v, FOLDER));
            }
        }
        File own = ctx.getExternalFilesDir("backup");
        if (own != null) out.add(own);
        return out;
    }

    /** Writes a backup to every folder that accepts it. Returns the files written; empty when none did. */
    static List<File> create(Context ctx) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("created", System.currentTimeMillis());
        root.put("prefs", encode(Prefs.all(ctx)));
        root.put("usage", encode(UsageTracker.all(ctx)));
        byte[] data = root.toString(1).getBytes(StandardCharsets.UTF_8);
        String name = PREFIX + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
        List<File> written = new ArrayList<>();
        for (File dir : folders(ctx)) {
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) continue;
                File f = new File(dir, name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
                written.add(f);
                prune(dir);
            } catch (Exception ignored) {
                // A drive that is read-only or not allowed: try the next one.
            }
        }
        return written;
    }

    /** Backups found in every folder, newest first. */
    static List<File> find(Context ctx) {
        List<File> out = new ArrayList<>();
        for (File dir : folders(ctx)) {
            out.addAll(backupsIn(dir));
            File legacy = new File(dir.getParentFile(), LEGACY_FOLDER);
            if (dir.getName().equals(FOLDER) && legacy.isDirectory()) out.addAll(backupsIn(legacy));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** Puts a backup back. Returns null on success, otherwise a short reason; nothing changes on failure. */
    static String restore(Context ctx, File file) {
        try {
            if (file.length() > MAX_BYTES) return "file too large";
            byte[] data;
            try (InputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                data = bos.toByteArray();
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (root.optInt("format", 0) != FORMAT) return "unknown format";
            Map<String, Object> prefs = decode(root.getJSONObject("prefs"));
            Map<String, Object> usage = decode(root.optJSONObject("usage"));
            Prefs.replaceAll(ctx, prefs);
            UsageTracker.replaceAll(ctx, usage);
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** When the backup was made, or the file's date. */
    static long created(File f) {
        return f.lastModified();
    }

    private static List<File> backupsIn(File dir) {
        List<File> out = new ArrayList<>();
        try {
            File[] files = dir.listFiles();
            if (files == null) return out;
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && (n.startsWith(PREFIX) || n.startsWith(LEGACY_PREFIX)) && n.endsWith(".json")) out.add(f);
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    private static void prune(File dir) {
        List<File> all = backupsIn(dir);
        Collections.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < all.size(); i++) all.get(i).delete();
    }

    /** Each value as {"t": type letter, "v": value}, so booleans, numbers and text come back as they were. */
    private static JSONObject encode(Map<String, ?> values) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) item.put("t", "b");
            else if (v instanceof Integer) item.put("t", "i");
            else if (v instanceof Long) item.put("t", "l");
            else if (v instanceof Float) item.put("t", "f");
            else if (v instanceof String) item.put("t", "s");
            else continue;
            item.put("v", v);
            out.put(e.getKey(), item);
        }
        return out;
    }

    private static Map<String, Object> decode(JSONObject json) throws Exception {
        Map<String, Object> out = new HashMap<>();
        if (json == null) return out;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject item = json.getJSONObject(key);
            switch (item.getString("t")) {
                case "b":
                    out.put(key, item.getBoolean("v"));
                    break;
                case "i":
                    out.put(key, item.getInt("v"));
                    break;
                case "l":
                    out.put(key, item.getLong("v"));
                    break;
                case "f":
                    out.put(key, (float) item.getDouble("v"));
                    break;
                case "s":
                    out.put(key, item.getString("v"));
                    break;
                default:
                    break;
            }
        }
        return out;
    }
}
