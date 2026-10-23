/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hook diretti sul WindowManager (system_server) per dare finestre libere ai
 * display diversi dal telefono, scavalcando eventuali modifiche HyperOS al
 * percorso classico (force_desktop_mode_on_external_displays).
 *
 *  - DisplayContent.forceDesktopMode()            -> true   se mDisplayId != 0
 *  - DisplayWindowSettings.getWindowingModeLocked -> FREEFORM (5) se il display != 0
 *  - ActivityTaskManagerService.mSupportsFreeformWindowManagement -> true
 *    (impostato dopo retrieveSettings, che lo legge solo al boot)
 */
public final class WmHooks {

    static final boolean ENABLED = false;  // v0.14: app a tutto schermo sul monitor (scelta utente)
    private static final int WINDOWING_MODE_FREEFORM = 5;

    public static void hook(LoadPackageParam lp) {
        if (!ENABLED) return;
        hookForceDesktopMode(lp);
        hookWindowingMode(lp);
        hookFreeformSupport(lp);
    }

    // DisplayContent.forceDesktopMode() -> true per display != 0
    private static void hookForceDesktopMode(LoadPackageParam lp) {
        Class<?> dc = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayContent", lp.classLoader);
        if (dc == null) { XLog.i("[wm] DisplayContent non trovata"); return; }
        boolean found = false;
        for (Method m : dc.getDeclaredMethods()) {
            if (!m.getName().equals("forceDesktopMode") || m.getReturnType() != boolean.class) continue;
            found = true;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int id = displayIdOf(p.thisObject);
                        Object orig = p.getResult();
                        if (id != 0) {
                            p.setResult(Boolean.TRUE);
                            XLog.i("[wm] forceDesktopMode(display " + id + ") " + orig + " -> true");
                        }
                    }
                });
                XLog.i("[wm] hook su DisplayContent.forceDesktopMode");
            } catch (Throwable t) { XLog.e("[wm] hook forceDesktopMode fallito", t); }
        }
        if (!found) XLog.i("[wm] DisplayContent.forceDesktopMode NON presente in questa build");
    }

    // DisplayWindowSettings.getWindowingModeLocked(...) -> FREEFORM per display != 0
    private static void hookWindowingMode(LoadPackageParam lp) {
        Class<?> dws = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayWindowSettings", lp.classLoader);
        if (dws == null) { XLog.i("[wm] DisplayWindowSettings non trovata"); return; }
        int n = 0;
        for (Method m : dws.getDeclaredMethods()) {
            if (!m.getName().equals("getWindowingModeLocked") || m.getReturnType() != int.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        int id = -1;
                        for (Object a : p.args) {
                            if (a != null && a.getClass().getName().endsWith("DisplayContent")) {
                                id = displayIdOf(a); break;
                            }
                        }
                        Object orig = p.getResult();
                        if (id > 0) {
                            p.setResult(WINDOWING_MODE_FREEFORM);
                            XLog.i("[wm] getWindowingModeLocked(display " + id + ") " + orig + " -> freeform");
                        } else {
                            XLog.i("[wm] getWindowingModeLocked(display " + id + ") = " + orig);
                        }
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[wm] hook getWindowingModeLocked fallito", t); }
        }
        XLog.i("[wm] hook su " + n + " varianti di getWindowingModeLocked");
    }

    // ActivityTaskManagerService.mSupportsFreeformWindowManagement -> true dopo retrieveSettings
    private static void hookFreeformSupport(LoadPackageParam lp) {
        Class<?> atms = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityTaskManagerService", lp.classLoader);
        if (atms == null) { XLog.i("[wm] ActivityTaskManagerService non trovata"); return; }
        boolean found = false;
        for (Method m : atms.getDeclaredMethods()) {
            if (!m.getName().equals("retrieveSettings")) continue;
            found = true;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            boolean before = XposedHelpers.getBooleanField(p.thisObject, "mSupportsFreeformWindowManagement");
                            XposedHelpers.setBooleanField(p.thisObject, "mSupportsFreeformWindowManagement", true);
                            XLog.i("[wm] mSupportsFreeformWindowManagement " + before + " -> true");
                        } catch (Throwable t) { XLog.e("[wm] set mSupportsFreeformWindowManagement fallito", t); }
                    }
                });
                XLog.i("[wm] hook su ATMS.retrieveSettings");
            } catch (Throwable t) { XLog.e("[wm] hook retrieveSettings fallito", t); }
        }
        if (!found) XLog.i("[wm] ATMS.retrieveSettings NON presente");
    }

    private static int displayIdOf(Object displayContent) {
        try { return XposedHelpers.getIntField(displayContent, "mDisplayId"); }
        catch (Throwable t) {
            try { return (Integer) XposedHelpers.callMethod(displayContent, "getDisplayId"); }
            catch (Throwable t2) { return -1; }
        }
    }

    private WmHooks() {}
}
