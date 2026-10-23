/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import de.robv.android.xposed.XposedHelpers;

/**
 * Spegne il pannello del TELEFONO mentre il monitor e' in modalita' desktop
 * (system_server). Stessa tecnica di scrcpy --turn-screen-off: power mode OFF
 * sul solo display interno via SurfaceControl, lasciando sveglio il sistema.
 *
 *  - spegne ~2 s dopo che il display esterno fisico diventa un display desktop
 *    (se il telefono e' bloccato, aspetta lo sblocco);
 *  - riaccende quando il display esterno viene rimosso (cavo staccato);
 *  - una volta per collegamento: se l'utente blocca e risveglia il telefono,
 *    il pannello torna acceso e resta acceso (via d'uscita).
 */
public final class PhoneScreen {

    static final boolean TURN_OFF_ON_DESKTOP = true;
    static final int     DELAY_MS            = 2000;

    private static final int POWER_MODE_OFF    = 0;
    private static final int POWER_MODE_NORMAL = 2;

    private static ClassLoader cl;
    private static Handler handler;
    private static volatile boolean turnedOff        = false;
    private static volatile boolean pendingUnlock    = false;
    private static volatile boolean receiverRegistered = false;

    static void init(ClassLoader classLoader) { cl = classLoader; }

    /** Chiamato da ExternalDisplayGate quando compare un display esterno fisico. */
    static void onExternalAdded() {
        if (!TURN_OFF_ON_DESKTOP) return;
        handler().postDelayed(new Runnable() {
            @Override public void run() { tryTurnOff("collegamento"); }
        }, DELAY_MS);
    }

    /** Chiamato da ExternalDisplayGate quando il display esterno sparisce. */
    static void onExternalRemoved() {
        pendingUnlock = false;
        if (!turnedOff) return;
        turnedOff = false;
        handler().post(new Runnable() {
            @Override public void run() {
                if (isInteractive()) {
                    if (setInternalPower(POWER_MODE_NORMAL)) XLog.i("[screen] pannello telefono RIACCESO");
                } else {
                    XLog.i("[screen] telefono in standby: la riaccensione la gestisce il sistema al risveglio");
                }
            }
        });
    }

    private static void tryTurnOff(String why) {
        if (!ExternalDisplayGate.present || turnedOff) return;
        Context ctx = systemContext();
        if (ctx == null) { XLog.i("[screen] nessun Context di sistema"); return; }
        if (!isInteractive()) { XLog.i("[screen] telefono in standby, non spengo"); return; }
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            pendingUnlock = true;
            registerUnlockReceiver(ctx);
            XLog.i("[screen] telefono bloccato: spengo il pannello dopo lo sblocco");
            return;
        }
        if (setInternalPower(POWER_MODE_OFF)) {
            turnedOff = true;
            XLog.i("[screen] pannello telefono SPENTO (" + why + ")");
        }
    }

    private static void registerUnlockReceiver(Context ctx) {
        if (receiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (pendingUnlock) {
                        pendingUnlock = false;
                        handler().postDelayed(new Runnable() {
                            @Override public void run() { tryTurnOff("sblocco"); }
                        }, 800);
                    }
                }
            };
            ctx.registerReceiver(r, new IntentFilter(Intent.ACTION_USER_PRESENT),
                    null, handler(), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) { XLog.e("[screen] registerReceiver fallito", t); }
    }

    // ------------------------------------------------------------------
    // SurfaceControl
    // ------------------------------------------------------------------
    private static boolean setInternalPower(int mode) {
        try {
            IBinder token = internalToken();
            if (token == null) { XLog.i("[screen] token del display interno non trovato"); return false; }
            Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", cl);
            XposedHelpers.callStaticMethod(sc, "setDisplayPowerMode", token, mode);
            return true;
        } catch (Throwable t) {
            XLog.e("[screen] setDisplayPowerMode(" + mode + ") fallito", t);
            return false;
        }
    }

    private static IBinder internalToken() {
        Class<?> sc = XposedHelpers.findClass("android.view.SurfaceControl", cl);
        Class<?> dc = XposedHelpers.findClassIfExists("com.android.server.display.DisplayControl", cl);
        Class<?> holder = dc != null ? dc : sc;   // Android 14+: DisplayControl
        long[] ids = (long[]) XposedHelpers.callStaticMethod(holder, "getPhysicalDisplayIds");
        if (ids == null || ids.length == 0) return null;
        IBinder first = null;
        for (long id : ids) {
            IBinder tok = (IBinder) XposedHelpers.callStaticMethod(holder, "getPhysicalDisplayToken", id);
            if (tok == null) continue;
            if (first == null) first = tok;
            Boolean internal = isInternal(sc, id, tok);
            if (Boolean.TRUE.equals(internal)) return tok;
        }
        return first;
    }

    private static Boolean isInternal(Class<?> sc, long id, IBinder tok) {
        Object info = null;
        try { info = XposedHelpers.callStaticMethod(sc, "getStaticDisplayInfo", id); }
        catch (Throwable ignored) {
            try { info = XposedHelpers.callStaticMethod(sc, "getStaticDisplayInfo", tok); }
            catch (Throwable ignored2) {}
        }
        if (info == null) return null;
        try { return XposedHelpers.getBooleanField(info, "isInternal"); }
        catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------
    // utilita'
    // ------------------------------------------------------------------
    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-screen");
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    private static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) { return null; }
    }

    private static boolean isInteractive() {
        Context ctx = systemContext();
        if (ctx == null) return true;
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private PhoneScreen() {}
}
