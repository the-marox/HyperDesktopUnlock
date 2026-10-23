/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Forza i gate "desktop supportato" di WMShell / framework.
 *
 * v0.9: aggiunta la classe DesktopState e il trattamento per-display:
 * i metodi booleani il cui nome contiene "OnDisplay" (es.
 * isDesktopModeSupportedOnDisplay(displayId)) vengono forzati a true SOLO
 * quando il displayId e' diverso da 0 (il telefono). Cosi' il monitor viene
 * trattato da desktop (finestre libere con barra del titolo) e lo schermo del
 * telefono resta normale.
 */
public final class DesktopModeHooks {

    // ============================================================
    static final boolean FORCE_SUPPORTED            = true;
    static final boolean FORCE_TOGGLE_ON            = true;
    static final boolean FORCE_DISABLE_RESTRICTIONS = false;  // RISCHIO bootloop
    static final boolean DISCOVERY_LOG              = false;  // spento (v0.11)
    // ============================================================

    private static final String[] CANDIDATE_CLASSES = {
        "com.android.wm.shell.desktopmode.DesktopModeStatus",
        "com.android.wm.shell.shared.desktopmode.DesktopModeStatus",
        "com.android.wm.shell.shared.desktopmode.DesktopState",
        "com.android.wm.shell.desktopmode.DesktopState",
        "com.android.window.flags.DesktopModeStatus",
        "android.window.DesktopModeFlags"
    };

    private static final String[] TOGGLE_GETTERS = {
        "getToggleOverride", "getRawToggleOverride", "getToggleOverrideFromSystem"
    };

    /** Metodi gia' noti e rumorosi: non loggarli. */
    private static final List<String> DISCOVERY_SKIP = Arrays.asList(
        "isTrue", "isFlagTrue", "getToggleOverride", "getRawToggleOverride",
        "getToggleOverrideFromSystem", "isDesktopModeForcedEnabled");

    /**
     * Classi del framework (system_server) da trattare SOLO per-display:
     * nessuna forzatura globale (sospetta dietro il bootloop), solo i metodi
     * "OnDisplay" per display != 0, piu' la ricognizione.
     */
    private static final String[] PER_DISPLAY_ONLY_CLASSES = {
        "com.android.server.wm.DesktopModeHelper"
    };

    /** v0.12: gate globali del framework forzati a true SOLO con monitor collegato. */
    static final boolean FORCE_FRAMEWORK_WHEN_EXTERNAL = false;  // v0.13: spento, causava crash SystemUI senza benefici
    private static final List<String> FRAMEWORK_GATED = Arrays.asList(
        "isDesktopModeSupported", "isDeviceEligibleForDesktopMode", "canEnterDesktopMode",
        "isDesktopModeDevOptionsSupported", "isDesktopModeEnabledByDevOption");

    private static final java.util.Set<String> GATED_LOGGED =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Logga una sola volta per metodo che la forzatura e' entrata in azione. */
    private static void logGatedOnce(String where, String name) {
        if (GATED_LOGGED.add(name)) {
            XLog.i("[" + where + "] " + name + "() -> true (gate monitor attivo)");
        }
    }

    public static void hook(LoadPackageParam lpparam, String where) {
        boolean found = false;
        for (String cn : CANDIDATE_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cn, lpparam.classLoader);
            if (cls == null) continue;
            found = true;
            XLog.i("[" + where + "] trovata classe gate: " + cn);
            hookClass(lpparam.classLoader, cls, where, true);
        }
        for (String cn : PER_DISPLAY_ONLY_CLASSES) {
            Class<?> cls = XposedHelpers.findClassIfExists(cn, lpparam.classLoader);
            if (cls == null) continue;
            found = true;
            XLog.i("[" + where + "] trovata classe gate (solo per-display): " + cn);
            hookClass(lpparam.classLoader, cls, where, false);
        }
        if (!found) {
            XLog.i("[" + where + "] nessuna classe DesktopModeStatus/Flags in questo processo");
        }
    }

    private static void hookClass(ClassLoader cl, Class<?> cls, final String where,
                                  final boolean globalForces) {
        final Object overrideOn = (FORCE_TOGGLE_ON && globalForces) ? findOverrideOn(cl, cls) : null;

        for (final Method m : cls.getDeclaredMethods()) {
            final String name = m.getName();

            // --- Per-display: forza true solo per display != 0 ---
            if (FORCE_SUPPORTED && m.getReturnType() == boolean.class && name.contains("OnDisplay")) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Integer did = firstInt(p.args);
                            if (did != null && did != 0) {
                                p.setResult(Boolean.TRUE);
                                XLog.i("[" + where + "] " + name + "(display " + did + ") -> true (forzato)");
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Integer did = firstInt(p.args);
                            if (did == null || did == 0) {
                                XLog.i("[" + where + "] " + name + "(display " + did + ") = " + p.getResult());
                            }
                        }
                    });
                    XLog.i("[" + where + "] hook per-display su " + name);
                } catch (Throwable t) { XLog.e("hook per-display fallito su " + name, t); }
                continue;
            }

            if (DISCOVERY_LOG && !DISCOVERY_SKIP.contains(name)) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            XLog.i("[" + where + "] " + name + "(" + argsOf(p.args) + ") = " + p.getResult());
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // --- Getter dell'enum ToggleOverride -> OVERRIDE_ON ---
            if (overrideOn != null && isToggleGetter(name)
                    && m.getReturnType().isInstance(overrideOn)) {
                forceObject(m, overrideOn, where);
                continue;
            }

            if (m.getReturnType() != boolean.class) continue;

            if (!globalForces) {
                // Framework (DesktopModeHelper): forza true SOLO mentre un monitor
                // esterno e' collegato (ExternalDisplayGate). All'avvio resta tutto
                // com'e', quindi niente rischio bootloop.
                if (FORCE_FRAMEWORK_WHEN_EXTERNAL && FRAMEWORK_GATED.contains(name)) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                if (ExternalDisplayGate.present) {
                                    p.setResult(Boolean.TRUE);
                                    logGatedOnce(where, name);
                                }
                            }
                        });
                        XLog.i("[" + where + "] hook con gate monitor su " + name);
                    } catch (Throwable t) { XLog.e("hook gated fallito su " + name, t); }
                }
                continue;
            }

            if (FORCE_SUPPORTED &&
                    (name.equals("isDesktopModeSupported")
                     || name.equals("canEnterDesktopMode")
                     || name.equals("canShowDesktopModeDevOption")
                     || name.equals("canShowDesktopExperienceDevOption"))) {
                forceBool(m, true, where);
            } else if (FORCE_TOGGLE_ON && name.equals("isDesktopModeForcedEnabled")) {
                forceBool(m, true, where);
            } else if (FORCE_DISABLE_RESTRICTIONS && name.equals("enforceDeviceRestrictions")) {
                forceBool(m, false, where);
            }
        }
    }

    private static Integer firstInt(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof Integer) return (Integer) a;
        return null;
    }

    private static String argsOf(Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            String s = String.valueOf(args[i]);
            if (s.length() > 50) s = s.substring(0, 50) + "…";
            sb.append(s);
        }
        return sb.toString();
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
            Class<?> e = XposedHelpers.findClassIfExists(en, cl);
            Object c = constByName(e, "OVERRIDE_ON");
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

    private static void forceBool(Method m, boolean value, String where) {
        try {
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
            XLog.i("[" + where + "] override " + m.getName() + "() -> " + value);
        } catch (Throwable t) { XLog.e("hook fallito su " + m.getName(), t); }
    }

    private static void forceObject(Method m, Object value, String where) {
        try {
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(value));
            XLog.i("[" + where + "] override " + m.getName() + "() -> " + value);
        } catch (Throwable t) { XLog.e("hook fallito su " + m.getName(), t); }
    }

    private DesktopModeHooks() {}
}
