/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Forza i gate "desktop supportato" di WMShell / framework.
 *
 *  - Classi dei gate (DesktopModeStatus, DesktopModeFlags...): i metodi
 *    "desktop supportato" restituiscono true e il ToggleOverride diventa
 *    OVERRIDE_ON (cosi' compare anche l'opzione sviluppatore).
 *  - Metodi per-display (nome con "OnDisplay", es. isDesktopModeSupportedOnDisplay):
 *    true SOLO per display diversi da 0, quindi il monitor e' trattato da
 *    desktop e lo schermo del telefono resta normale.
 *  - DesktopModeHelper (system_server): solo i metodi per-display. Forzarne i
 *    gate globali faceva crashare SystemUI su HyperOS 4 beta senza benefici, e
 *    disattivare enforceDeviceRestrictions porta al bootloop: non lo facciamo.
 *    (Il codice sperimentale e' nella storia del progetto, tag v1.4.1.)
 */
public final class DesktopModeHooks {

    private static final String[] GATE_CLASSES = {
        "com.android.wm.shell.desktopmode.DesktopModeStatus",
        "com.android.wm.shell.shared.desktopmode.DesktopModeStatus",
        "com.android.wm.shell.shared.desktopmode.DesktopState",
        "com.android.wm.shell.desktopmode.DesktopState",
        "com.android.window.flags.DesktopModeStatus",
        "android.window.DesktopModeFlags"
    };

    /** Classi del framework trattate SOLO per-display. */
    private static final String[] PER_DISPLAY_ONLY_CLASSES = {
        "com.android.server.wm.DesktopModeHelper"
    };

    private static final List<String> FORCED_TRUE = Arrays.asList(
        "isDesktopModeSupported", "canEnterDesktopMode", "isDesktopModeForcedEnabled",
        "canShowDesktopModeDevOption", "canShowDesktopExperienceDevOption");

    private static final String[] TOGGLE_GETTERS = {
        "getToggleOverride", "getRawToggleOverride", "getToggleOverrideFromSystem"
    };

    /** Ogni forzatura per-display viene registrata nel log una sola volta. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<String>());

    public static void hook(LoadPackageParam lp, String where) {
        int n = 0;
        for (String cn : GATE_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cn, lp.classLoader);
            if (cls != null) n += hookClass(lp.classLoader, cls, where, true);
        }
        for (String cn : PER_DISPLAY_ONLY_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cn, lp.classLoader);
            if (cls != null) n += hookClass(lp.classLoader, cls, where, false);
        }
        if (n == 0) XLog.i("[" + where + "] nessun gate desktop trovato in questo processo");
        Diag.mark("desktop-flags", n > 0);
    }

    private static int hookClass(ClassLoader cl, Class<?> cls, final String where, boolean globalForces) {
        Object overrideOn = globalForces ? findOverrideOn(cl, cls) : null;
        int n = 0;
        for (final Method m : cls.getDeclaredMethods()) {
            final String name = m.getName();
            if (m.getReturnType() == boolean.class && name.contains("OnDisplay")) {
                if (hookPerDisplay(m, where)) n++;
                continue;
            }
            if (!globalForces) continue;
            if (overrideOn != null && isToggleGetter(name) && m.getReturnType().isInstance(overrideOn)) {
                if (force(m, overrideOn)) n++;
            } else if (m.getReturnType() == boolean.class && FORCED_TRUE.contains(name)) {
                if (force(m, Boolean.TRUE)) n++;
            }
        }
        XLog.i("[" + where + "] " + cls.getName() + ": " + n + " gate forzati");
        return n;
    }

    private static boolean hookPerDisplay(Method m, final String where) {
        final String name = m.getName();
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Integer did = firstInt(p.args);
                    if (did != null && did != 0) {
                        p.setResult(Boolean.TRUE);
                        if (LOGGED.add(name + "#" + did)) {
                            XLog.i("[" + where + "] " + name + "(display " + did + ") -> true");
                        }
                    }
                }
            });
            return true;
        } catch (Throwable t) { XLog.e("[" + where + "] hook per-display fallito su " + name, t); return false; }
    }

    private static Integer firstInt(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof Integer) return (Integer) a;
        return null;
    }

    private static boolean isToggleGetter(String name) {
        for (String g : TOGGLE_GETTERS) if (g.equals(name)) return true;
        return false;
    }

    private static Object findOverrideOn(ClassLoader cl, Class<?> cls) {
        String[] enumNames = {
            "android.window.DesktopModeFlags$ToggleOverride",
            cls.getName() + "$ToggleOverride"
        };
        for (String en : enumNames) {
            Object c = constByName(XposedHelpers.findClassIfExists(en, cl), "OVERRIDE_ON");
            if (c != null) return c;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!isToggleGetter(m.getName())) continue;
            Object c = constByName(m.getReturnType(), "OVERRIDE_ON");
            if (c != null) return c;
        }
        return null;
    }

    private static Object constByName(Class<?> e, String name) {
        if (e == null) return null;
        try {
            Object[] cs = e.getEnumConstants();
            if (cs == null) return null;
            for (Object c : cs) if (name.equals(((Enum<?>) c).name())) return c;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean force(Method m, Object value) {
        try {
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
            return true;
        } catch (Throwable t) { XLog.e("hook fallito su " + m.getName(), t); return false; }
    }

    private DesktopModeHooks() {}
}
