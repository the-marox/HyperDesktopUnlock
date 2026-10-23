/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import de.robv.android.xposed.XposedBridge;

/**
 * Logger minimale. Tutto finisce nel log di Xposed, leggibile con:
 *   adb logcat -s LSPosed-Bridge HyperDesktop
 * oppure dal gestore LSPosed -> Log.
 */
public final class XLog {
    public static final String TAG = "HyperDesktop";

    public static void i(String m) {
        XposedBridge.log(TAG + ": " + m);
    }

    public static void e(String m, Throwable t) {
        XposedBridge.log(TAG + ": " + m);
        XposedBridge.log(t);
    }

    private XLog() {}
}
