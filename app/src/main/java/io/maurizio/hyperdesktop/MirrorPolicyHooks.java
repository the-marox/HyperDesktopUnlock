/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Neutralizza l'enforcer del mirroring di Xiaomi (classe NON AOSP):
 *
 *   com.android.server.display.SecondaryDisplayPolicy
 *     .forceEnableMirrorBuiltInDisplaySettingIfNeeded()   <- chiamato da
 *         DisplayManagerService$SettingsObserver.onChange
 *     .enableMirrorBuiltInDisplaySetting()                <- scrive mirror=1
 *
 * Entrambi diventano no-op. Gli altri metodi della classe vengono loggati
 * (discovery) per capire se c'e' altro che forza il mirroring.
 */
public final class MirrorPolicyHooks {

    static final boolean NEUTER_POLICY = true;
    static final boolean DISCOVERY_LOG = false;

    private static final String CLS = "com.android.server.display.SecondaryDisplayPolicy";
    private static final String[] NEUTER = {
        "forceEnableMirrorBuiltInDisplaySettingIfNeeded",
        "enableMirrorBuiltInDisplaySetting"
    };

    public static void hook(LoadPackageParam lp) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLS, lp.classLoader);
        if (cls == null) { XLog.i("[policy] " + CLS + " NON trovata"); return; }
        XLog.i("[policy] trovata " + CLS);

        for (Method m : cls.getDeclaredMethods()) {
            XLog.i("[policy]   metodo: " + m.getName()
                    + " ret=" + m.getReturnType().getSimpleName()
                    + " params=" + m.getParameterTypes().length);
        }

        for (final Method m : cls.getDeclaredMethods()) {
            final String name = m.getName();
            if (NEUTER_POLICY && isNeuter(name)) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam p) {
                            XLog.i("[policy] NEUTRALIZZATO " + name + "()");
                            Class<?> rt = ((Method) p.method).getReturnType();
                            if (rt == boolean.class) return Boolean.FALSE;
                            if (rt == int.class)     return 0;
                            return null;
                        }
                    });
                    XLog.i("[policy] no-op installato su " + name);
                } catch (Throwable t) { XLog.e("[policy] hook fallito su " + name, t); }
            } else if (DISCOVERY_LOG) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            XLog.i("[policy] " + name + "() = " + p.getResult());
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isNeuter(String name) {
        for (String n : NEUTER) if (n.equals(name)) return true;
        return false;
    }

    private MirrorPolicyHooks() {}
}
