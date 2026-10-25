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
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.view.Display;

import de.robv.android.xposed.XposedHelpers;

/**
 * Opzioni della sessione desktop (system_server), guidate da ExternalDisplayGate.
 * Applicate al collegamento del monitor e di nuovo quando l'app manda ACTION_APPLY
 * (permesso con firma: solo l'app del modulo puo' mandarlo), cosi' valgono subito
 * senza ricollegare il cavo.
 *
 *  - densita' (`wm density`): IWindowManager.setForcedDisplayDensityForUser.
 *  - risoluzione logica (`wm size`): IWindowManager.setForcedDisplaySize.
 *  - frequenza di aggiornamento: Display.setUserPreferredDisplayMode, SOLO scegliendo
 *    tra le modalita' che il monitor dichiara di supportare (mai una inventata, cosi'
 *    non si rischia di spegnere il monitor).
 *  - "tieni acceso": un wakelock impedisce il timeout dello schermo in desktop.
 *
 * Per ognuna: chiave assente = non tocchiamo nulla; "0" = valore nativo del monitor.
 * Riapplichiamo solo se il valore e' diverso da quello attuale.
 *
 * Tutto gira sul thread di AltTabHooks, mai dentro gli hook del WindowManager.
 */
final class DesktopSession {

    private static final int USER_CURRENT  = -2;
    private static final int APPLY_DELAY_MS = 1500;   // display completamente registrato
    private static final int RECONNECT_DELAY_MS = 2800;   // dopo la configurazione: rifa' il giro di focus

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
                if (ExternalDisplayGate.externalId == displayId) applyDisplayConfig(displayId);
            }
        }, APPLY_DELAY_MS);
        AltTabHooks.handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (ExternalDisplayGate.externalId == displayId) InputReconnect.nudge("collegamento");
            }
        }, RECONNECT_DELAY_MS);
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
                    PhoneScreen.onSettingsChanged();   // touchpad acceso/spento mentre il telefono e' nero
                    applyDisplayConfig(ExternalDisplayGate.externalId);
                }
            };
            ctx.registerReceiver(r, new IntentFilter(Prefs.ACTION_APPLY), Prefs.PERMISSION_APPLY,
                    AltTabHooks.handler(), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) { XLog.e("[session] registerReceiver fallito", t); }
    }

    // ------------------------------------------------------------------
    // configurazione del display (densita' + risoluzione + frequenza)
    // ------------------------------------------------------------------
    private static void applyDisplayConfig(int displayId) {
        if (displayId <= 0) return;
        applyRefresh(displayId);   // modalita' fisica prima
        applySize(displayId);      // risoluzione logica
        applyDensity(displayId);   // densita' logica
    }

    private static Object wms() {
        return XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "getWindowManagerService");
    }

    private static void applyDensity(int displayId) {
        if (!Prefs.has(Prefs.K_DENSITY)) return;
        int dpi = Prefs.density();
        if (dpi == Prefs.DENSITY_INVALID) return;
        if (dpi != 0 && (dpi < Prefs.DENSITY_MIN || dpi > Prefs.DENSITY_MAX)) {
            XLog.i("[session] densita' " + dpi + " fuori intervallo, ignorata"); return;
        }
        try {
            Object wm = wms();
            int base    = intCall(wm, "getBaseDisplayDensity", displayId);
            int initial = intCall(wm, "getInitialDisplayDensity", displayId);
            if (dpi == 0) {
                if (base > 0 && base == initial) return;
                XposedHelpers.callMethod(wm, "clearForcedDisplayDensityForUser", displayId, USER_CURRENT);
                XLog.i("[session] densita' del display " + displayId + " -> nativa (" + initial + " dpi)");
            } else {
                if (base == dpi) return;
                XposedHelpers.callMethod(wm, "setForcedDisplayDensityForUser", displayId, dpi, USER_CURRENT);
                XLog.i("[session] densita' del display " + displayId + ": " + base + " -> " + dpi + " dpi");
            }
        } catch (Throwable t) { XLog.e("[session] impostazione densita' fallita", t); }
    }

    private static void applySize(int displayId) {
        if (!Prefs.has(Prefs.K_RESOLUTION)) return;
        String s = Prefs.resolution();
        try {
            Object wm = wms();
            Point base = new Point();
            XposedHelpers.callMethod(wm, "getBaseDisplaySize", displayId, base);
            Point init = new Point();
            XposedHelpers.callMethod(wm, "getInitialDisplaySize", displayId, init);
            int[] wh = s.equals("0") ? null : parseSize(s);
            // "Nativa" ("0"), valore non valido o orientamento errato -> misura
            // fisica nativa. IMPORTANTE: su HyperOS NON si azzera la forzatura
            // (clearForcedDisplaySize), perche' senza una misura esplicita il
            // sistema ripiega sulla risoluzione del telefono in verticale. Quindi
            // per la "nativa" imponiamo esplicitamente WxH fisici: sempre una
            // dimensione esplicita, cosi' il monitor resta orizzontale.
            boolean toNative = wh == null || ((init.x >= init.y) != (wh[0] >= wh[1]));
            int tw = toNative ? init.x : wh[0];
            int th = toNative ? init.y : wh[1];
            if (wh == null && !s.equals("0"))
                XLog.i("[session] risoluzione '" + s + "' non valida/orientamento errato, uso la nativa");
            if (base.x == tw && base.y == th) return;
            XposedHelpers.callMethod(wm, "setForcedDisplaySize", displayId, tw, th);
            XLog.i("[session] risoluzione del display " + displayId + ": " + base.x + "x" + base.y
                    + " -> " + tw + "x" + th + (toNative ? " (nativa)" : ""));
        } catch (Throwable t) { XLog.e("[session] impostazione risoluzione fallita", t); }
    }

    private static void applyRefresh(int displayId) {
        if (!Prefs.has(Prefs.K_REFRESH)) return;
        String s = Prefs.refreshRate();
        try {
            Context ctx = AltTabHooks.systemContext();
            DisplayManager dm = ctx != null ? ctx.getSystemService(DisplayManager.class) : null;
            Display d = dm != null ? dm.getDisplay(displayId) : null;
            if (d == null) return;
            if (s.equals("0")) {
                // @SystemApi, non nell'SDK pubblico: via reflection (siamo in system_server)
                XposedHelpers.callMethod(d, "clearUserPreferredDisplayMode");
                XLog.i("[session] frequenza del display " + displayId + " -> nativa");
                return;
            }
            int hz;
            try { hz = Integer.parseInt(s); } catch (NumberFormatException e) { return; }
            Display.Mode cur = d.getMode();
            Display.Mode best = null;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                        || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (Math.round(m.getRefreshRate()) != hz) continue;
                best = m; break;
            }
            if (best == null) {
                XLog.i("[session] il monitor non supporta " + cur.getPhysicalWidth() + "x"
                        + cur.getPhysicalHeight() + "@" + hz + "Hz, frequenza ignorata");
                return;
            }
            if (best.getModeId() == cur.getModeId()) return;
            XposedHelpers.callMethod(d, "setUserPreferredDisplayMode", best);
            XLog.i("[session] frequenza del display " + displayId + " -> " + hz + "Hz (mode " + best.getModeId() + ")");
        } catch (Throwable t) { XLog.e("[session] impostazione frequenza fallita", t); }
    }

    private static int[] parseSize(String s) {
        int x = s.indexOf('x');
        if (x < 0) x = s.indexOf('X');
        if (x <= 0 || x >= s.length() - 1) return null;
        try {
            int w = Integer.parseInt(s.substring(0, x).trim());
            int h = Integer.parseInt(s.substring(x + 1).trim());
            if (w < 320 || w > 7680 || h < 240 || h > 4320) return null;
            return new int[] { w, h };
        } catch (NumberFormatException e) { return null; }
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
