package com.termux.app;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** Fixed dark/light appearance switch (no runtime color picking — the
 * palettes are plain values/ vs values-night/ resources, selected through
 * AppCompat night mode). "dark" is the default. */
public final class AiThemeMode {

    public static final String PREF_KEY = "ai_theme_mode";
    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private AiThemeMode() {}

    public static String mode(Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, DARK);
    }

    /** Call before super.onCreate() in activities; also safe to call anytime —
     * AppCompat recreates live activities when the mode flips. */
    public static void apply(Context context) {
        boolean light = LIGHT.equals(mode(context));
        int mode = light ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
        if (AppCompatDelegate.getDefaultNightMode() != mode)
            AppCompatDelegate.setDefaultNightMode(mode);
    }
}
