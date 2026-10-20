package com.bbu.sbl;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads what an app offers on the Android TV home screen: its "Watch Next" entries (continue watching)
 * and the programs of its preview channels (featured items). Both live in the system TV provider and are
 * published by the apps themselves; an app that publishes nothing simply has nothing to show.
 *
 * Reading other apps' programs needs a TV-listings permission. Where the TV does not grant it, the query
 * throws a SecurityException and the hero stays hidden: see {@link #probe}.
 */
final class FeaturedLoader {
    /** The hero shows a handful of items, one at a time. */
    static final int MAX_ITEMS = 4;
    static final int OK = 0, DENIED = 1, UNAVAILABLE = 2;

    static final class Item {
        final String pkg;
        final String title;
        /** Episode line such as "S2 E4 · Title", or empty. */
        final String subtitle;
        final String description;
        final Uri image;
        /** A short preview clip, or null. */
        final Uri video;
        final String intentUri;
        /** 0 to 100, or -1 when the program has no playback position. */
        final int progress;
        /** Minutes left, or -1 when unknown. */
        final int minutesLeft;
        final boolean watchNext;
        /** TYPE_TV_EPISODE: an episode of a series, which goes first. */
        boolean episode;
        /** The program's TvContract type (TYPE_MOVIE, TYPE_TRACK, ...), or -1. */
        int type = -1;
        /** WATCH_NEXT_TYPE_CONTINUE, NEXT, NEW or WATCHLIST; -1 when unknown or not a Watch Next program. */
        int watchNextType = -1;
        /** When the viewer last engaged with it, in UTC milliseconds; 0 when unknown. */
        long engagement;
        /** The series an episode belongs to, so only one episode per series is shown. */
        String seriesId = "";

        /** Something the viewer started and has not finished (the default when the app gives no Watch Next type). */
        boolean isContinue() {
            return watchNext && (watchNextType <= TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        }

        Item(String pkg, String title, String subtitle, String description, Uri image, Uri video,
             String intentUri, int progress, int minutesLeft, boolean watchNext) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
            this.image = image;
            this.video = video;
            this.intentUri = intentUri;
            this.progress = progress;
            this.minutesLeft = minutesLeft;
            this.watchNext = watchNext;
        }
    }

    /** Columns every program has; the second list is asked for first and dropped on a provider that lacks some. */
    private static final String[] BASE_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_TYPE,
    };
    private static final String[] EXTRA_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_SERIES_ID,
    };
    /** Only Watch Next programs have these. */
    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
    };

    /** Pictures kept in memory, by size: the hero shows one at a time, so a few are enough. */
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Lets go of the cached pictures (the launcher is hidden or the system is short of memory). */
    static void trim() {
        IMAGES.evictAll();
    }

    /** Debug builds only: fake items for every favourite, to try the hero on a TV that publishes none. */
    private static List<Item> demoItems;

    private FeaturedLoader() {
    }

    /**
     * Whether this TV lets the launcher read other apps' programs: OK, DENIED (READ_TV_LISTINGS not granted)
     * or UNAVAILABLE (no provider). READ_TV_LISTINGS is a normal runtime permission, so it can be asked for
     * on screen; the provider then returns the programs apps marked searchable.
     */
    static int probe(Context ctx) {
        if (ctx.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) return DENIED;
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[]{TvContract.WatchNextPrograms.COLUMN_TITLE}, null, null, null)) {
            return c == null ? UNAVAILABLE : OK;
        } catch (SecurityException e) {
            return DENIED;
        } catch (Exception e) {
            return UNAVAILABLE;
        }
    }

    static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    /** Debug builds only: {@code adb shell am start -n com.bbu.sbl/.MainActivity --es demo_featured <base url>}. */
    static void setDemo(Context ctx, String baseUrl) {
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (baseUrl == null || baseUrl.isEmpty()) {
            demoItems = null;
            return;
        }
        String b = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        List<Item> d = new ArrayList<>();
        d.add(new Item("", "Sintel", "S1 E1 \u00B7 The Beginning",
                "A lonely young woman, Sintel, helps and befriends a dragon, whom she calls Scales. But when he is "
                        + "kidnapped by an adult dragon, Sintel decides to embark on a dangerous quest to find her lost friend.",
                Uri.parse(b + "demo1.jpg"), Uri.parse(b + "demo.mp4"), "", 42, 31, true));
        d.add(new Item("", "Aurora", "", "A short film about light over the northern sky.",
                Uri.parse(b + "demo2.jpg"), null, "", 8, 74, true));
        d.add(new Item("", "New in your list", "S3 E8", "Pick up where you left off.",
                Uri.parse(b + "demo3.jpg"), null, "", -1, -1, false));
        demoItems = d;
    }

    /** Blocking: call from a background thread. Never throws; returns what could be read. */
    static List<Item> load(Context ctx, String pkg) {
        if (demoItems != null) {
            List<Item> out = new ArrayList<>();
            for (Item i : demoItems) {
                out.add(new Item(pkg, i.title, i.subtitle, i.description, i.image, i.video, i.intentUri,
                        i.progress, i.minutesLeft, i.watchNext));
            }
            return out;
        }
        List<Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        query(ctx, TvContract.WatchNextPrograms.CONTENT_URI, pkg, true, all, seen);
        query(ctx, TvContract.PreviewPrograms.CONTENT_URI, pkg, false, all, seen);
        // Watch Next before featured; in Watch Next, things being watched first, then TV episodes, then the
        // most recently engaged with. The sort is stable, so the app's own order is kept otherwise.
        java.util.Collections.sort(all, (a, b) -> {
            if (a.watchNext != b.watchNext) return a.watchNext ? -1 : 1;
            if (a.isContinue() != b.isContinue()) return a.isContinue() ? -1 : 1;
            if (a.episode != b.episode) return a.episode ? -1 : 1;
            return Long.compare(b.engagement, a.engagement);
        });
        // Only one episode per series (a started one and the next one are the same show).
        Set<String> series = new HashSet<>();
        List<Item> out = new ArrayList<>();
        for (Item item : all) {
            if (item.episode && !item.seriesId.isEmpty() && !series.add(item.seriesId)) continue;
            out.add(item);
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long num(Cursor c, String column, long fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
    }

    private static void query(Context ctx, Uri base, String pkg, boolean watchNext, List<Item> out, Set<String> seen) {
        // No selection: without ACCESS_ALL_EPG_DATA the provider refuses one. With READ_TV_LISTINGS it returns
        // the rows apps marked searchable, and the package parameter narrows them to the app we want.
        Uri uri = base.buildUpon().appendQueryParameter("package", pkg).build();   // TvContract.PARAM_PACKAGE is hidden
        List<String> wanted = new ArrayList<>(java.util.Arrays.asList(BASE_COLUMNS));
        wanted.addAll(java.util.Arrays.asList(EXTRA_COLUMNS));
        if (watchNext) wanted.addAll(java.util.Arrays.asList(WATCH_NEXT_COLUMNS));
        Cursor c = null;
        try {
            try {
                c = ctx.getContentResolver().query(uri, wanted.toArray(new String[0]), null, null, null);
            } catch (SecurityException e) {
                return;
            } catch (Exception e) {
                // A TV provider without some of the newer columns: ask for the basics only.
                c = ctx.getContentResolver().query(uri, BASE_COLUMNS, null, null, null);
            }
            if (c == null) return;
            while (c.moveToNext() && out.size() < 40) {
                String title = str(c, TvContract.PreviewPrograms.COLUMN_TITLE);
                if (title == null || title.trim().isEmpty()) continue;
                String intent = str(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
                if (intent == null || !seen.add(title + "|" + intent)) continue;
                String thumb = str(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
                String poster = str(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
                // The hero is landscape: a thumbnail suits it better than a portrait poster.
                String image = thumb != null && !thumb.isEmpty() ? thumb : poster;
                long pos = num(c, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
                long dur = num(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0);
                int progress = dur > 0 && pos > 0 ? (int) Math.max(1, Math.min(100, pos * 100 / dur)) : -1;
                int left = dur > 0 && pos > 0 ? (int) Math.max(1, (dur - pos) / 60000L) : -1;
                String longDesc = str(c, TvContract.PreviewPrograms.COLUMN_LONG_DESCRIPTION);
                String shortDesc = str(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
                String desc = longDesc != null && !longDesc.trim().isEmpty() ? longDesc.trim() : shortDesc == null ? "" : shortDesc.trim();
                String clip = str(c, TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI);
                Uri video = clip != null && !clip.isEmpty() ? Uri.parse(clip) : null;
                String subtitle = episodeLine(str(c, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                        str(c, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                        str(c, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE));
                Item item = new Item(pkg, title.trim(), subtitle, desc,
                        image == null || image.isEmpty() ? null : Uri.parse(image), video, intent, progress, left, watchNext);
                item.type = (int) num(c, TvContract.PreviewPrograms.COLUMN_TYPE, -1);
                item.episode = item.type == TvContract.PreviewPrograms.TYPE_TV_EPISODE;
                String seriesId = str(c, TvContract.PreviewPrograms.COLUMN_SERIES_ID);
                if (seriesId != null) item.seriesId = seriesId;
                if (watchNext) {
                    item.watchNextType = (int) num(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1);
                    item.engagement = num(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0);
                }
                out.add(item);
            }
        } catch (Exception ignored) {
            // No permission or no provider: nothing to show.
        } finally {
            if (c != null) c.close();
        }
    }

    private static String episodeLine(String season, String episode, String episodeTitle) {
        StringBuilder sb = new StringBuilder();
        if (season != null && !season.isEmpty()) sb.append('S').append(season);
        if (episode != null && !episode.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append('E').append(episode);
        if (episodeTitle != null && !episodeTitle.trim().isEmpty()) {
            sb.append(sb.length() > 0 ? " \u00B7 " : "").append(episodeTitle.trim());
        }
        return sb.toString();
    }

    /** Music apps that do not always label their programs as music. */
    private static final String[] MUSIC_APPS = {"spotify", "deezer", "apple.android.music", "youtube.tvmusic",
            "youtube.music", "tidal", "amazon.mp3", "soundcloud", "napster", "fizy", "muud", "pandora", "iheart"};

    /** True when the program is something to listen to: a music type, or a program of a well-known music app. */
    static boolean isMusic(Item item) {
        int t = item.type;
        if (t == TvContract.PreviewPrograms.TYPE_TRACK || t == TvContract.PreviewPrograms.TYPE_ALBUM
                || t == TvContract.PreviewPrograms.TYPE_ARTIST || t == TvContract.PreviewPrograms.TYPE_PLAYLIST
                || t == TvContract.PreviewPrograms.TYPE_STATION) return true;
        if (t == TvContract.PreviewPrograms.TYPE_MOVIE || t == TvContract.PreviewPrograms.TYPE_TV_EPISODE
                || t == TvContract.PreviewPrograms.TYPE_TV_SERIES || t == TvContract.PreviewPrograms.TYPE_TV_SEASON) return false;
        String p = item.pkg == null ? "" : item.pkg.toLowerCase(java.util.Locale.ROOT);
        for (String m : MUSIC_APPS) if (p.contains(m)) return true;
        return false;
    }

    /** The intent that opens a program, always pinned to the app that published it. */
    static Intent intentFor(Item item) {
        try {
            Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            i.setSelector(null);
            i.setPackage(item.pkg);
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap cached(Uri uri) {
        return uri == null ? null : IMAGES.get(uri.toString());
    }

    /** Blocking: decodes a program image at about the size it is drawn. Returns null on any problem. */
    static Bitmap loadImage(Context ctx, Uri uri, int reqWidth, int reqHeight) {
        if (uri == null) return null;
        Bitmap hit = IMAGES.get(uri.toString());
        if (hit != null) return hit;
        try {
            byte[] data = readBytes(ctx, uri);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            // Keep at least the drawn size, so the picture is only ever scaled down and stays sharp.
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqWidth && o.outHeight / (sample * 2) >= reqHeight) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp != null) IMAGES.put(uri.toString(), bmp);
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static byte[] readBytes(Context ctx, Uri uri) throws Exception {
        String scheme = uri.getScheme();
        InputStream in;
        HttpURLConnection conn = null;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            in = conn.getInputStream();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme) || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme)) {
            in = ctx.getContentResolver().openInputStream(uri);
        } else {
            return null;
        }
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > 6 * 1024 * 1024) return null;   // a hero picture is never this large
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
