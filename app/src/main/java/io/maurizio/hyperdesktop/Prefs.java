/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.os.SystemClock;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Impostazioni lette DAGLI HOOK (system_server, SystemUI).
 *
 * Le scrive l'app del modulo (SettingsActivity) con MODE_WORLD_READABLE; LSPosed
 * (meta-data xposedsharedprefs) le rende leggibili ai processi agganciati.
 * Ogni opzione e' letta al momento dell'uso e il file viene ricontrollato al
 * massimo una volta al secondo, quindi le modifiche valgono senza riavvio e la
 * lettura costa pochissimo anche sul thread dell'input.
 *
 * Le costanti qui sotto sono condivise con SettingsActivity: sono tutte costanti
 * di compilazione, quindi l'app non carica questa classe (ne' l'API Xposed).
 */
final class Prefs {

    static final String PKG  = "io.maurizio.hyperdesktop";
    static final String FILE = "settings";

    /** Broadcast con cui l'app chiede a system_server di riapplicare subito le impostazioni. */
    static final String ACTION_APPLY     = "io.maurizio.hyperdesktop.APPLY_SETTINGS";
    static final String PERMISSION_APPLY = "io.maurizio.hyperdesktop.permission.APPLY_SETTINGS";

    static final String K_LAUNCHER_PKG   = "launcher_pkg";
    static final String K_AUTO_LAUNCH    = "auto_launch";
    static final String K_MANAGE_A11Y    = "manage_accessibility";
    static final String K_PHONE_OFF      = "phone_screen_off";
    static final String K_SHORTCUTS      = "shortcuts";
    static final String K_SWITCHER       = "switcher_preview";
    static final String K_DENSITY        = "monitor_density";
    static final String K_KEEP_AWAKE     = "keep_awake";

    static final String  D_LAUNCHER_PKG  = "cu.axel.smartdock";
    static final boolean D_AUTO_LAUNCH   = true;
    static final boolean D_MANAGE_A11Y   = true;
    static final boolean D_PHONE_OFF     = true;
    static final boolean D_SHORTCUTS     = true;
    static final boolean D_SWITCHER      = true;
    static final boolean D_KEEP_AWAKE    = false;

    /**
     * Densita': chiave assente = non toccare; 0 = predefinita del monitor;
     * altrimenti valori tra MIN e MAX. Un valore illeggibile vale INVALID (ignorato).
     */
    static final int DENSITY_DEFAULT = 0;
    static final int DENSITY_INVALID = -1;
    static final int DENSITY_MIN     = 100;
    static final int DENSITY_MAX     = 800;

    private static final long RECHECK_MS = 1000;
    private static final long RETRY_MS   = 30_000;   // se l'apertura fallisce, niente tentativi a ogni tasto

    private static XSharedPreferences xp;
    private static long lastCheck;
    private static long failedAt;

    /** @return null se le impostazioni non sono disponibili (si usano i predefiniti). */
    private static synchronized XSharedPreferences p() {
        long now = SystemClock.uptimeMillis();
        if (xp == null) {
            if (failedAt != 0 && now - failedAt < RETRY_MS) return null;
            try {
                xp = new XSharedPreferences(PKG, FILE);
                lastCheck = now;
            } catch (Throwable t) {
                failedAt = now;
                XLog.e("[prefs] apertura impostazioni fallita, uso i predefiniti", t);
                return null;
            }
        } else if (now - lastCheck >= RECHECK_MS) {
            lastCheck = now;
            try { xp.reload(); } catch (Throwable ignored) {}   // rilegge solo se il file e' cambiato
        }
        return xp;
    }

    /** Prima apertura all'avvio del processo, fuori dai percorsi critici (input). */
    static void warmUp() { p(); }

    /** Rilettura immediata (dopo il broadcast di SettingsActivity). */
    static synchronized void refresh() {
        if (xp == null) { p(); return; }
        lastCheck = SystemClock.uptimeMillis();
        try { xp.reload(); } catch (Throwable ignored) {}
    }

    static boolean has(String k) {
        try { XSharedPreferences x = p(); return x != null && x.contains(k); }
        catch (Throwable t) { return false; }
    }

    static boolean bool(String k, boolean def) {
        try { XSharedPreferences x = p(); return x != null ? x.getBoolean(k, def) : def; }
        catch (Throwable t) { return def; }
    }

    static String str(String k, String def) {
        try {
            XSharedPreferences x = p();
            String v = x != null ? x.getString(k, def) : def;
            return (v == null || v.trim().isEmpty()) ? def : v.trim();
        } catch (Throwable t) { return def; }
    }

    static int integer(String k, int def) {
        try { return Integer.parseInt(str(k, String.valueOf(def))); } catch (Throwable t) { return def; }
    }

    static String  launcherPkg()   { return str(K_LAUNCHER_PKG, D_LAUNCHER_PKG); }
    static boolean autoLaunch()    { return bool(K_AUTO_LAUNCH, D_AUTO_LAUNCH); }
    static boolean manageA11y()    { return bool(K_MANAGE_A11Y, D_MANAGE_A11Y); }
    static boolean phoneOff()      { return bool(K_PHONE_OFF, D_PHONE_OFF); }
    static boolean shortcuts()     { return bool(K_SHORTCUTS, D_SHORTCUTS); }
    static boolean switcherPreview() { return bool(K_SWITCHER, D_SWITCHER); }
    static boolean keepAwake()     { return bool(K_KEEP_AWAKE, D_KEEP_AWAKE); }
    static int     density()       { return integer(K_DENSITY, DENSITY_INVALID); }

    /** Per la riga di diagnostica; null se questo processo non usa le impostazioni. */
    static synchronized String describeIfLoaded() {
        if (xp == null) return failedAt != 0 ? "settings: unavailable, using defaults" : null;
        try {
            File f = xp.getFile();
            if (f.canRead()) return "settings: saved";
            if (f.exists())  return "settings: NOT readable (" + f + ")";
            return "settings: defaults";
        } catch (Throwable t) { return "settings: unavailable (" + t.getClass().getSimpleName() + ")"; }
    }

    private Prefs() {}
}
