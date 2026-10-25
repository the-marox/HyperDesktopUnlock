/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Punto di ingresso del modulo (dichiarato in assets/xposed_init).
 *
 * LSPosed carica questo metodo in OGNI processo che rientra nello "scope"
 * del modulo. Qui smistiamo in base al processo:
 *   - "android"              = system_server (il framework)
 *   - "com.android.systemui" = SystemUI (finestra di collegamento, avvio del dock)
 *   - "com.android.settings" = l'app Impostazioni (pagina Display esterni)
 * A fine caricamento ogni processo stampa una riga [diag] con gli hook agganciati.
 *
 * Lo scope va impostato in LSPosed su: System Framework, System UI, Impostazioni.
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final LoadPackageParam lp) {
        switch (lp.packageName) {
            case "android":
                safe("gate",          () -> ExternalDisplayGate.hook(lp));
                safe("desktop-flags", () -> DesktopModeHooks.hook(lp, "system_server"));
                safe("mirror-guard",  () -> SettingsGuardHooks.hook(lp));
                safe("mirror-policy", () -> MirrorPolicyHooks.hook(lp));
                safe("keys",          () -> AltTabHooks.hook(lp));
                safe("control",       ModuleControl::startSystemServer);
                Prefs.warmUp();
                Diag.summary("system_server");
                break;
            case "com.android.systemui":
                safe("desktop-flags", () -> DesktopModeHooks.hook(lp, "systemui"));
                safe("dialog",        () -> ConnectDialogHooks.hook(lp));
                safe("auto-launch",   () -> LauncherHooks.hook(lp));
                safe("report",        () -> ModuleControl.hookSystemUi(lp));
                Prefs.warmUp();
                Diag.summary(lp.processName);
                break;
            case "com.android.settings":
                safe("topology-fix",  () -> SettingsHooks.hook(lp));
                Diag.summary(lp.processName);
                break;
            default:
                // ignorato
        }
    }

    /**
     * Un hook che fallisce (es. classe cambiata da un aggiornamento di HyperOS)
     * non deve impedire l'installazione degli altri ne' la riga [diag].
     */
    private static void safe(String name, Runnable install) {
        try {
            install.run();
        } catch (Throwable t) {
            XLog.e("[main] installazione di '" + name + "' fallita", t);
            Diag.mark(name, false);
        }
    }
}
