/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Logger minimale. Ogni riga va in due posti:
 *  - logcat, tag "HyperDesktop" (sempre, indipendente dalle impostazioni di LSPosed):
 *      adb logcat -s HyperDesktop
 *  - il registro dei moduli di LSPosed (XposedBridge.log).
 */
public final class XLog {
    public static final String TAG = "HyperDesktop";

    public static void i(String m) {
        try { Log.i(TAG, m); } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + ": " + m); } catch (Throwable ignored) {}
    }

    public static void e(String m, Throwable t) {
        try { Log.e(TAG, m, t); } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + ": " + m); XposedBridge.log(t); } catch (Throwable ignored) {}
    }

    private XLog() {}
}
