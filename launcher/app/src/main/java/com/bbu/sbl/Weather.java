package com.bbu.sbl;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Current weather for the place the TV is in, from Open-Meteo (free, no key). The place comes from the TV's
 * last known location when it has one, otherwise from its internet address through geojs.io (rough, city level;
 * the only lookup service, picked because it states that it keeps no access logs). Only
 * coordinates and a weather request leave the TV; the answer is cached for half an hour.
 */
final class Weather {
    static final class Reading {
        final double tempC;
        final int code;
        final boolean day;

        Reading(double tempC, int code, boolean day) {
            this.tempC = tempC;
            this.code = code;
            this.day = day;
        }
    }

    private static final String FILE = "sbl_weather";
    private static final long MAX_AGE_MS = 30 * 60 * 1000L;
    private static final long LOCATION_AGE_MS = 24 * 60 * 60 * 1000L;

    private Weather() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** "c" or "f": the owner's choice, otherwise Fahrenheit only where it is the habit (United States). */
    static String unit(Context c) {
        String chosen = Prefs.getString(c, Prefs.WEATHER_UNIT, null);
        if (chosen != null) return chosen;
        return "US".equals(Locale.getDefault().getCountry()) ? "f" : "c";
    }

    static Reading cached(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("temp")) return null;
        return new Reading(Double.longBitsToDouble(p.getLong("temp", 0L)), p.getInt("code", 3), p.getBoolean("day", true));
    }

    static boolean stale(Context c) {
        return System.currentTimeMillis() - sp(c).getLong("time", 0L) > MAX_AGE_MS;
    }

    /** The saved position, without looking anything up; null until it was found once. */
    static double[] knownLocation(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains("lat")) return null;
        return new double[]{Double.longBitsToDouble(p.getLong("lat", 0L)), Double.longBitsToDouble(p.getLong("lon", 0L))};
    }

    /** Blocking: finds (and saves) the position. Call from a background thread. */
    static double[] locate(Context c) {
        return location(c);
    }

    /** Blocking: call from a background thread. Returns the new reading, or the cached one when it fails. */
    static Reading fetch(Context c) {
        try {
            double[] where = location(c);
            if (where == null) return cached(c);
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto", where[0], where[1]);
            JSONObject cur = new JSONObject(get(url)).getJSONObject("current");
            double temp = cur.getDouble("temperature_2m");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            sp(c).edit().putLong("temp", Double.doubleToRawLongBits(temp)).putInt("code", code)
                    .putBoolean("day", day).putLong("time", System.currentTimeMillis()).apply();
            return new Reading(temp, code, day);
        } catch (Exception e) {
            return cached(c);
        }
    }

    /** Latitude and longitude: the device's last known position, the one saved a day ago at most, or the internet address's. */
    private static double[] location(Context c) {
        SharedPreferences p = sp(c);
        if (System.currentTimeMillis() - p.getLong("loc_time", 0L) < LOCATION_AGE_MS && p.contains("lat")) {
            return new double[]{Double.longBitsToDouble(p.getLong("lat", 0L)), Double.longBitsToDouble(p.getLong("lon", 0L))};
        }
        double[] found = deviceLocation(c);
        if (found == null) found = addressLocation();
        if (found == null) return p.contains("lat")
                ? new double[]{Double.longBitsToDouble(p.getLong("lat", 0L)), Double.longBitsToDouble(p.getLong("lon", 0L))} : null;
        p.edit().putLong("lat", Double.doubleToRawLongBits(found[0])).putLong("lon", Double.doubleToRawLongBits(found[1]))
                .putLong("loc_time", System.currentTimeMillis()).apply();
        return found;
    }

    private static double[] deviceLocation(Context c) {
        try {
            if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            return best == null ? null : new double[]{best.getLatitude(), best.getLongitude()};
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] addressLocation() {
        try {
            JSONObject j = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
            return new double[]{Double.parseDouble(j.getString("latitude")), Double.parseDouble(j.getString("longitude"))};
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 64 * 1024) throw new Exception("too large");
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** The temperature in the owner's unit, rounded, with a degree sign. */
    static String temperature(Context c, double tempC) {
        boolean f = "f".equals(unit(c));
        long v = Math.round(f ? tempC * 9.0 / 5.0 + 32.0 : tempC);
        return v + "\u00B0";
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static int labelFor(int code) {
        if (code <= 1) return R.string.weather_clear;
        if (code == 2) return R.string.weather_partly;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.string.weather_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_storm;
        return R.string.weather_cloudy;
    }

    static int iconFor(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_w_clear : R.drawable.ic_w_night;
        if (code == 2) return R.drawable.ic_w_partly;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_w_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_cloud;
    }
}
