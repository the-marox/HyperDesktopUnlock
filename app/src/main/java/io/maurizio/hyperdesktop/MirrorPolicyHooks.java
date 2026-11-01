/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
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
 * Entrambi diventano no-op.
 */
public final class MirrorPolicyHooks {

    private static final String CLS = "com.android.server.display.SecondaryDisplayPolicy";
    private static final Set<String> NEUTER = new HashSet<>(Arrays.asList(
        "forceEnableMirrorBuiltInDisplaySettingIfNeeded",
        "enableMirrorBuiltInDisplaySetting"));

    public static void hook(LoadPackageParam lp) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLS, lp.classLoader);
        if (cls == null) { XLog.i("[policy] " + CLS + " NON trovata"); Diag.mark("mirror-policy", false); return; }

        Set<String> done = new HashSet<>();
        for (final Method m : cls.getDeclaredMethods()) {
            final String name = m.getName();
            if (!NEUTER.contains(name)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!ModuleState.enabled()) return;   // interruttore spento: politica originale
                        XLog.i("[policy] neutralizzato " + name + "()");
                        Class<?> rt = ((Method) p.method).getReturnType();
                        if (rt == boolean.class)  p.setResult(Boolean.FALSE);
                        else if (rt == int.class) p.setResult(0);
                        else                      p.setResult(null);
                    }
                });
                done.add(name);
            } catch (Throwable t) { XLog.e("[policy] hook fallito su " + name, t); }
        }
        XLog.i("[policy] no-op su " + done.size() + "/" + NEUTER.size() + " metodi " + done);
        Diag.mark("mirror-policy", done.size() == NEUTER.size());
    }

    private MirrorPolicyHooks() {}
}
