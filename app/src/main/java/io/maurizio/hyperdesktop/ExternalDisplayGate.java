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
 * Tiene traccia (in system_server) della presenza di un display ESTERNO
 * fisico, agganciando creazione e rimozione dei DisplayContent del WindowManager.
 *
 * Serve come interruttore di sicurezza: le forzature "desktop supportato" sul
 * framework valgono solo mentre un monitor e' collegato, cosi' l'avvio del
 * telefono (senza monitor) resta identico e non rischia bootloop.
 */
public final class ExternalDisplayGate {

    /** true mentre almeno un display esterno fisico e' collegato. */
    public static volatile boolean present = false;

    public static void hook(LoadPackageParam lp) {
        Class<?> dc = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayContent", lp.classLoader);
        if (dc == null) { XLog.i("[gate] DisplayContent non trovata"); return; }
        PhoneScreen.init(lp.classLoader);

        XposedBridge.hookAllConstructors(dc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (isExternal(p.thisObject)) {
                    present = true;
                    XLog.i("[gate] display esterno COLLEGATO (id " + displayIdOf(p.thisObject) + ")");
                    PhoneScreen.onExternalAdded();
                }
            }
        });

        int n = 0;
        for (Method m : dc.getDeclaredMethods()) {
            if (!m.getName().equals("removeImmediately") && !m.getName().equals("remove")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (isExternal(p.thisObject) && present) {
                            present = false;
                            XLog.i("[gate] display esterno RIMOSSO");
                            PhoneScreen.onExternalRemoved();
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {}
        }
        XLog.i("[gate] hook installati (costruttori + " + n + " metodi di rimozione)");
    }

    private static boolean isExternal(Object displayContent) {
        int id = displayIdOf(displayContent);
        if (id == 0) return false;
        try {
            Object d = XposedHelpers.getObjectField(displayContent, "mDisplay");
            // Display.getType() e TYPE_EXTERNAL (=2) sono @hide: via riflessione
            if (d != null) return ((Integer) XposedHelpers.callMethod(d, "getType")) == 2;
        } catch (Throwable ignored) {}
        // Nessun fallback: se il tipo non e' leggibile NON lo trattiamo come
        // esterno (display virtuali di registrazione/cast non devono contare).
        return false;
    }

    static int displayIdOf(Object displayContent) {
        try { return XposedHelpers.getIntField(displayContent, "mDisplayId"); }
        catch (Throwable t) {
            try { return (Integer) XposedHelpers.callMethod(displayContent, "getDisplayId"); }
            catch (Throwable t2) { return -1; }
        }
    }

    private ExternalDisplayGate() {}
}
