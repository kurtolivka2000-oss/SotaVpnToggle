package com.example.sotavpntoggle;

import android.content.Context;
import android.content.SharedPreferences;

/** Хранение настройки: часть подписи плитки, по которой её искать. */
public final class Prefs {
    private static final String FILE = "settings";
    private static final String KEY_LABEL = "tile_label";
    public static final String DEFAULT_LABEL = "Sota";

    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getLabel(Context c) {
        String v = sp(c).getString(KEY_LABEL, DEFAULT_LABEL);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_LABEL : v.trim();
    }

    public static void setLabel(Context c, String label) {
        sp(c).edit().putString(KEY_LABEL, label == null ? "" : label.trim()).apply();
    }
}
