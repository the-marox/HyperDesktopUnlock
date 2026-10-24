/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Logger minimale. Ogni riga va in due posti:
 *  - logcat, tag "HyperDesktop" (sempre, indipendente dalle impostazioni di LSPosed):
 *      adb logcat -s HyperDesktop
 *  - il registro dei moduli di LSPosed (XposedBridge.log).
 * In piu' le ultime righe restano in memoria nel processo, per il report
 * diagnostico dell'app (il registro di sistema su HyperOS si svuota in fretta).
 */
public final class XLog {
    public static final String TAG = "HyperDesktop";

    private static final int MAX_LINES  = 150;
    private static final int MAX_LINE   = 300;      // caratteri per riga nel report
    private static final int MAX_REPORT = 40_000;   // caratteri per processo nel report
    private static final ArrayDeque<Object[]> RECENT = new ArrayDeque<>(MAX_LINES);   // { Long ora, String riga }

    public static void i(String m) {
        try { Log.i(TAG, m); } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + ": " + m); } catch (Throwable ignored) {}
        remember(m);
    }

    public static void e(String m, Throwable t) {
        try { Log.e(TAG, m, t); } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + ": " + m); XposedBridge.log(t); } catch (Throwable ignored) {}
        String where = "";
        if (t != null && t.getStackTrace().length > 0) where = " @ " + t.getStackTrace()[0];
        remember("E " + m + ": " + t + where);
    }

    /** Economico (anche dentro gli hook sotto lock): l'ora si formatta solo nel report. */
    private static void remember(String m) {
        try {
            if (m != null && m.length() > MAX_LINE) m = m.substring(0, MAX_LINE) + "...";
            Object[] e = { System.currentTimeMillis(), m };
            synchronized (RECENT) {
                if (RECENT.size() >= MAX_LINES) RECENT.removeFirst();
                RECENT.addLast(e);
            }
        } catch (Throwable ignored) {}
    }

    /** Le ultime righe di questo processo, dalla piu' vecchia, entro MAX_REPORT caratteri. */
    static String recent() {
        Object[][] copy;
        synchronized (RECENT) { copy = RECENT.toArray(new Object[0][]); }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        ArrayDeque<String> out = new ArrayDeque<>();
        int size = 0;
        for (int k = copy.length - 1; k >= 0; k--) {
            String line = f.format(new Date((Long) copy[k][0])) + " " + copy[k][1] + "\n";
            if (size + line.length() > MAX_REPORT) break;
            out.addFirst(line);
            size += line.length();
        }
        StringBuilder sb = new StringBuilder(size);
        for (String s : out) sb.append(s);
        return sb.toString();
    }

    private XLog() {}
}
