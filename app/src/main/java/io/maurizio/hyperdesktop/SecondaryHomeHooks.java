/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Opzione "Non aprire il desktop di HyperOS sul monitor" (idea e prove dal fork
 * di oldbkroll, Xiaomi 17 Pro Max): su alcuni HyperOS il desktop di serie del
 * monitor (com.miui.home/.launcher.SecondaryDisplayLauncher) gira nello stesso
 * processo della home del telefono e, dopo un cambio di densita' o di scala,
 * le gesture del telefono smettono di funzionare.
 *
 * Con l'opzione accesa (spenta di serie):
 *  - in system_server si salta ActivityStartController.startHomeActivity solo
 *    quando l'attivita' e' esattamente quella e il display e' il monitor fisico;
 *    telefono, altri launcher (es. SmartDock) e app normali non sono toccati;
 *  - se quel desktop e' gia' aperto sul monitor (task con UNA sola attivita',
 *    proprio quella) lo si chiude, dal thread di AltTabHooks e mai sotto il lock
 *    del WindowManager. Non si ferma la home del telefono e non si cancellano dati.
 *
 * Sul monitor resta lo sfondo vuoto finche' non si apre un'app o il dock.
 */
final class SecondaryHomeHooks {

    static final String PKG = "com.miui.home";
    static final String CLS = "com.miui.home.launcher.SecondaryDisplayLauncher";

    /** Display su cui e' gia' stato scritto il log del blocco (uno per collegamento). */
    private static volatile int loggedDisplay = -1;

    static boolean active() {
        return ModuleState.enabled() && Prefs.blockStockHome();
    }

    static void hook(LoadPackageParam lp) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.server.wm.ActivityStartController", lp.classLoader);
        if (c == null) { XLog.i("[stock-home] ActivityStartController non trovata"); return; }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("startHomeActivity")) continue;
            final int infoIdx = indexOf(m.getParameterTypes(), ActivityInfo.class);
            final int areaIdx = indexOfName(m.getParameterTypes(), "TaskDisplayArea");
            if (infoIdx < 0 || areaIdx < 0) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            ActivityInfo info = (ActivityInfo) p.args[infoIdx];
                            Object area = p.args[areaIdx];
                            if (info == null || area == null) return;
                            if (!PKG.equals(info.packageName) || !CLS.equals(info.name)) return;
                            if (!active()) return;
                            Object dc = XposedHelpers.callMethod(area, "getDisplayContent");
                            int id = ExternalDisplayGate.displayIdOf(dc);
                            if (id <= 0 || !ExternalDisplayGate.present || id != ExternalDisplayGate.externalId) return;
                            p.setResult(null);
                            if (loggedDisplay != id) {
                                loggedDisplay = id;
                                XLog.i("[stock-home] desktop di HyperOS non aperto sul monitor (display " + id + ")");
                            }
                        } catch (Throwable t) { XLog.e("[stock-home] controllo dell'avvio fallito", t); }
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[stock-home] hook fallito", t); }
        }
        XLog.i("[stock-home] hook su " + n + " varianti di startHomeActivity");
        if (n > 0) Diag.mark("stock-home", true);   // assente su altri sistemi: non e' un errore
    }

    /** Monitor collegato o opzione appena accesa: chiude il desktop di serie se e' gia' aperto. */
    static void cleanupSoon(long delayMs) {
        try {
            AltTabHooks.handler().postDelayed(new Runnable() {
                @Override public void run() { cleanup(); }
            }, delayMs);
        } catch (Throwable t) { XLog.e("[stock-home] pianificazione fallita", t); }
    }

    static void onExternalRemoved() { loggedDisplay = -1; }

    private static void cleanup() {
        if (!active() || !ExternalDisplayGate.present) return;
        int display = ExternalDisplayGate.externalId;
        if (display <= 0) return;
        try {
            Object atm = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityTaskManager", null), "getService");
            List<?> roots = (List<?>) XposedHelpers.callMethod(atm, "getAllRootTaskInfosOnDisplay", display);
            if (roots == null) return;
            for (Object root : roots) {
                if (XposedHelpers.getIntField(root, "displayId") != display) continue;
                ComponentName top = (ComponentName) XposedHelpers.getObjectField(root, "topActivity");
                if (top == null || !PKG.equals(top.getPackageName()) || !CLS.equals(top.getClassName())) continue;
                if (XposedHelpers.getIntField(root, "numActivities") != 1) continue;
                int id = XposedHelpers.getIntField(root, "taskId");
                if (id <= 0) continue;
                Object removed = XposedHelpers.callMethod(atm, "removeTask", id);
                XLog.i("[stock-home] desktop di HyperOS gia' aperto sul monitor chiuso (task " + id + ", " + removed + ")");
            }
        } catch (Throwable t) { XLog.e("[stock-home] chiusura del desktop di serie fallita", t); }
    }

    private static int indexOf(Class<?>[] types, Class<?> want) {
        for (int i = 0; i < types.length; i++) if (types[i] == want) return i;
        return -1;
    }

    private static int indexOfName(Class<?>[] types, String simpleName) {
        for (int i = 0; i < types.length; i++) if (types[i].getSimpleName().equals(simpleName)) return i;
        return -1;
    }

    private SecondaryHomeHooks() {}
}
