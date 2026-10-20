package com.bbu.sbl;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Dark or light look. The colours live in values/colors.xml (dark) and values-notnight/colors.xml
 * (light); an activity picks one by pinning the night flag of its own configuration, whatever the TV says.
 */
final class UiMode {
    static final String DARK = "dark";
    static final String LIGHT = "light";
    /** Light by day, dark from sunset to sunrise at the TV's position. */
    static final String AUTO = "auto";

    private UiMode() {
    }

    static String mode(Context c) {
        return Prefs.getString(c, Prefs.UI_MODE, DARK);
    }

    /** The look in effect right now. */
    static boolean light(Context c) {
        String mode = mode(c);
        if (LIGHT.equals(mode)) return true;
        return AUTO.equals(mode) && daytime(c);
    }

    private static boolean daytime(Context c) {
        double[] where = Weather.knownLocation(c);
        if (where != null) return Sun.isDay(where[0], where[1], System.currentTimeMillis());
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);   // no position yet
        return hour >= 7 && hour < 19;
    }

    /** For attachBaseContext: the same context with the chosen language and look pinned. */
    static Context wrap(Context base) {
        Context lang = Lang.wrap(base);
        Configuration cfg = new Configuration(lang.getResources().getConfiguration());
        int night = light(base) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return lang.createConfigurationContext(cfg);
    }
}
