/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hook sull'app Impostazioni.
 *
 * La pagina "Display esterni" (ExternalDisplayPreferenceFragment, raggiungibile
 * solo via `am start`) crasha appena colleghi un monitor con:
 *
 *   UninitializedPropertyAccessException: lateinit property paneContent
 *   has not been initialized
 *   in DisplayTopologyPreferenceController.getPaneContent()
 *
 * Il colpevole e' il riquadro "topologia" (lo schema per disporre i due schermi):
 * l'adapter di preferenze miuix di Xiaomi non inizializza il layout AOSP, e il
 * campo lateinit resta vuoto. Fix: segnaliamo quel controller come NON disponibile
 * (getAvailabilityStatus -> 3 = UNSUPPORTED_ON_DEVICE), cosi' il riquadro non
 * viene nemmeno costruito e il resto della pagina puo' comparire.
 */
public final class SettingsHooks {

    // BasePreferenceController: AVAILABLE=0, AVAILABLE_UNSEARCHABLE=1,
    // CONDITIONALLY_UNAVAILABLE=2, UNSUPPORTED_ON_DEVICE=3, DISABLED_FOR_USER=4
    private static final int UNSUPPORTED_ON_DEVICE = 3;

    public static void hook(LoadPackageParam lpparam) {
        Class<?> ctrl = XposedHelpers.findClassIfExists(
            "com.android.settings.connecteddevice.display.DisplayTopologyPreferenceController",
            lpparam.classLoader);
        if (ctrl == null) {
            XLog.i("[settings] DisplayTopologyPreferenceController non trovato");
            Diag.mark("topology-fix", false);
            return;
        }
        boolean hooked = false;
        for (Method m : ctrl.getDeclaredMethods()) {
            if (m.getName().equals("getAvailabilityStatus")
                    && m.getParameterTypes().length == 0
                    && m.getReturnType() == int.class) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(UNSUPPORTED_ON_DEVICE));
                    hooked = true;
                } catch (Throwable t) {
                    XLog.e("[settings] hook topology fallito", t);
                }
            }
        }
        if (!hooked) XLog.i("[settings] getAvailabilityStatus() non trovato");
        Diag.mark("topology-fix", hooked);
    }

    private SettingsHooks() {}
}
