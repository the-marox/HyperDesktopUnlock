/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import de.robv.android.xposed.XposedHelpers;

/**
 * Opzioni della sessione desktop (system_server), guidate da ExternalDisplayGate:
 *
 *  - densita' del monitor: applicata al collegamento con
 *    IWindowManager.setForcedDisplayDensityForUser (come `wm density N -d id`).
 *    Chiave assente = non tocchiamo nulla; 0 = valore predefinito del monitor.
 *    Il WindowManager la ricorda per quel monitor, quindi la riapplichiamo solo
 *    se e' diversa da quella attuale (ogni cambio riconfigura le app sul monitor).
 *  - "tieni acceso": un wakelock tiene acceso il sistema finche' il monitor e'
 *    in desktop, cosi' il timeout dello schermo non lo spegne. Il tasto di
 *    accensione continua a funzionare normalmente.
 *
 * Quando l'utente salva queste opzioni, SettingsActivity manda ACTION_APPLY
 * (permesso con firma: solo l'app del modulo puo' mandarlo) e le riapplichiamo
 * subito, senza ricollegare il monitor.
 *
 * Tutto gira sul thread di AltTabHooks, mai dentro gli hook del WindowManager.
 */
final class DesktopSession {

    private static final int USER_CURRENT     = -2;
    private static final int DENSITY_DELAY_MS = 1500;   // display completamente registrato

    private static PowerManager.WakeLock wakeLock;
    private static boolean receiverRegistered;

    static void onExternalAdded(final int displayId) {
        AltTabHooks.handler().post(new Runnable() {
            @Override public void run() {
                registerApplyReceiver();
                setKeepAwake(Prefs.keepAwake());
            }
        });
        AltTabHooks.handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (ExternalDisplayGate.externalId == displayId) applyDensity(displayId);
            }
        }, DENSITY_DELAY_MS);
    }

    static void onExternalRemoved() {
        AltTabHooks.handler().post(new Runnable() {
            @Override public void run() { setKeepAwake(false); }
        });
    }

    // ------------------------------------------------------------------
    // "applica subito" da SettingsActivity
    // ------------------------------------------------------------------
    private static void registerApplyReceiver() {
        if (receiverRegistered) return;
        Context ctx = AltTabHooks.systemContext();
        if (ctx == null) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Prefs.refresh();
                    if (!ExternalDisplayGate.present) {   // valgono al prossimo collegamento
                        setKeepAwake(false);              // e un wakelock rimasto non deve restare
                        return;
                    }
                    XLog.i("[session] impostazioni aggiornate dall'app");
                    setKeepAwake(Prefs.keepAwake());
                    applyDensity(ExternalDisplayGate.externalId);
                }
            };
            ctx.registerReceiver(r, new IntentFilter(Prefs.ACTION_APPLY), Prefs.PERMISSION_APPLY,
                    AltTabHooks.handler(), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) { XLog.e("[session] registerReceiver fallito", t); }
    }

    // ------------------------------------------------------------------
    // densita'
    // ------------------------------------------------------------------
    private static void applyDensity(int displayId) {
        if (displayId <= 0 || !Prefs.has(Prefs.K_DENSITY)) return;
        int dpi = Prefs.density();
        if (dpi != Prefs.DENSITY_DEFAULT && (dpi < Prefs.DENSITY_MIN || dpi > Prefs.DENSITY_MAX)) {
            XLog.i("[session] densita' " + dpi + " fuori intervallo, ignorata");
            return;
        }
        try {
            Object wm = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "getWindowManagerService");
            int base    = intCall(wm, "getBaseDisplayDensity", displayId);
            int initial = intCall(wm, "getInitialDisplayDensity", displayId);
            if (dpi == Prefs.DENSITY_DEFAULT) {
                if (base > 0 && base == initial) return;   // gia' quella predefinita
                XposedHelpers.callMethod(wm, "clearForcedDisplayDensityForUser", displayId, USER_CURRENT);
                XLog.i("[session] densita' del display " + displayId + " riportata al valore predefinito (" + initial + " dpi)");
            } else {
                if (base == dpi) return;
                XposedHelpers.callMethod(wm, "setForcedDisplayDensityForUser", displayId, dpi, USER_CURRENT);
                XLog.i("[session] densita' del display " + displayId + ": " + base + " -> " + dpi + " dpi");
            }
        } catch (Throwable t) { XLog.e("[session] impostazione densita' fallita", t); }
    }

    private static int intCall(Object o, String method, int displayId) {
        try { return (Integer) XposedHelpers.callMethod(o, method, displayId); }
        catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------------------------
    // tieni acceso
    // ------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    private static void setKeepAwake(boolean on) {
        try {
            if (on) {
                if (!ExternalDisplayGate.present) return;
                if (wakeLock == null) {
                    Context ctx = AltTabHooks.systemContext();
                    if (ctx == null) return;
                    PowerManager pm = ctx.getSystemService(PowerManager.class);
                    wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "HyperDesktop:desktop");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                    XLog.i("[session] tieni acceso: ATTIVO");
                }
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                XLog.i("[session] tieni acceso: rilasciato");
            }
        } catch (Throwable t) { XLog.e("[session] wakelock fallito", t); }
    }

    private DesktopSession() {}
}
