package com.bbu.sbl;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The launcher's language. "Automatic" follows the TV's system language when SBL is translated into it and
 * uses English otherwise; any supported language can also be picked in the settings. The choice pins the locale
 * of the activity's own configuration (and the process default, which date formats and upper-casing read), so
 * the TV's system language does not have to offer it.
 */
final class Lang {
    static final String AUTO = "auto";

    /** Language tags SBL is translated into, with the name each is shown by in its own language. */
    static final String[] TAGS = {"en", "tr", "es", "fr", "de", "ru", "uk", "pt", "cs", "pl"};
    static final String[] NAMES = {"English", "T\u00FCrk\u00E7e", "Espa\u00F1ol", "Fran\u00E7ais", "Deutsch",
            "\u0420\u0443\u0441\u0441\u043A\u0438\u0439", "\u0423\u043A\u0440\u0430\u0457\u043D\u0441\u044C\u043A\u0430",
            "Portugu\u00EAs", "\u010Ce\u0161tina", "Polski"};

    private Lang() {
    }

    static String chosen(Context c) {
        return Prefs.getString(c, Prefs.LANGUAGE, AUTO);
    }

    static boolean supported(String language) {
        for (String t : TAGS) if (t.equals(language)) return true;
        return false;
    }

    /** The locale to pin, or null when the system's own choice already is the right one. */
    private static Locale override(Context base) {
        String chosen = chosen(base);
        if (supported(chosen)) return new Locale(chosen);
        Locale system = base.getResources().getConfiguration().getLocales().get(0);
        return supported(system.getLanguage()) ? null : Locale.ENGLISH;
    }

    /** For attachBaseContext: the same context with the language applied. */
    static Context wrap(Context base) {
        Locale locale = override(base);
        if (locale == null) return base;
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }
}
