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
 * Blocca, in system_server, le scritture che riportano il monitor in mirroring
 * o spengono il desktop:
 *   Secure.mirror_built_in_display = 1                  (enforcer del mirroring:
 *                                                        3 ms dopo la scelta Desktop)
 *   Global.force_desktop_mode_on_external_displays = 0  (reset del desktop)
 *   Global.enable_freeform_support = 0                  (idem)
 * Gli hook esistono solo in system_server: le scritture di SystemUI, Impostazioni
 * e shell restano libere, quindi la scelta "Mirroring" dell'utente funziona sempre.
 *
 * (Fino alla 1.4.1 si chiamava SettingsTraceHooks e registrava anche lo stack di
 *  ogni scrittura: e' cosi' che e' stato trovato SecondaryDisplayPolicy.)
 */
public final class SettingsGuardHooks {

    static final String KEY_MIRROR   = "mirror_built_in_display";
    static final String KEY_FORCE    = "force_desktop_mode_on_external_displays";
    static final String KEY_FREEFORM = "enable_freeform_support";
    private static final String[] KEYS = { KEY_MIRROR, KEY_FORCE, KEY_FREEFORM };

    public static void hook(LoadPackageParam lp) {
        int secure = hookTable(lp, "android.provider.Settings$Secure");
        int global = hookTable(lp, "android.provider.Settings$Global");
        XLog.i("[guard] hook su " + secure + " metodi put* di Secure e " + global + " di Global");
        Diag.mark("mirror-guard", secure > 0 && global > 0);
    }

    private static int hookTable(LoadPackageParam lp, String table) {
        Class<?> cls = XposedHelpers.findClassIfExists(table, lp.classLoader);
        if (cls == null) { XLog.i("[guard] " + table + " non trovata"); return 0; }
        final String tname = table.substring(table.indexOf('$') + 1);

        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String nm = m.getName();
            if (!(nm.startsWith("putString") || nm.startsWith("putInt"))) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        int ki = indexOfKey(p.args);
                        if (ki < 0) return;
                        String key = (String) p.args[ki];
                        String val = (ki + 1 < p.args.length) ? String.valueOf(p.args[ki + 1]) : null;
                        boolean block = KEY_MIRROR.equals(key) ? "1".equals(val) : "0".equals(val);
                        if (block) {
                            XLog.i("[guard] bloccata " + tname + "." + key + " = " + val);
                            p.setResult(Boolean.TRUE);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    private static int indexOfKey(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof String)) continue;
            for (String k : KEYS) if (k.equals(args[i])) return i;
        }
        return -1;
    }

    private SettingsGuardHooks() {}
}
