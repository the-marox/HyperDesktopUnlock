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
 *   - "android"             = system_server (il framework)
 *   - "com.android.systemui = SystemUI, dove gira WMShell (la logica desktop)
 *   - "com.android.settings = l'app Impostazioni (dove avviene il crash)
 *
 * Lo scope va impostato in LSPosed su: System Framework, System UI, Impostazioni.
 */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case "android":
                ExternalDisplayGate.hook(lpparam);
                DesktopModeHooks.hook(lpparam, "system_server");
                SettingsTraceHooks.hook(lpparam, "system_server");
                MirrorPolicyHooks.hook(lpparam);
                WmHooks.hook(lpparam);
                AltTabHooks.hook(lpparam);
                break;
            case "com.android.systemui":
                DesktopModeHooks.hook(lpparam, "systemui");
                ConnectDialogHooks.hook(lpparam);
                LauncherHooks.hook(lpparam);
                SettingsTraceHooks.hook(lpparam, "systemui");
                break;
            case "com.android.settings":
                SettingsHooks.hook(lpparam);
                break;
            default:
                // ignorato
        }
    }
}
