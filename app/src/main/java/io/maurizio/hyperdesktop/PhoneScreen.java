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
import android.provider.Settings;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.XposedHelpers;

/**
 * "Spegne" lo schermo del TELEFONO mentre il monitor e' in modalita' desktop
 * (system_server), in uno di due modi:
 *
 *  - pannello spento (predefinito): power mode OFF sul solo display interno via
 *    SurfaceControl, come scrcpy --turn-screen-off; il sistema resta sveglio.
 *    Su questo telefono si addormenta anche il sensore touch.
 *  - nero con touchpad (opzione): pannello acceso ma coperto da una finestra nera
 *    a luminosita' minima che usa i tocchi come touchpad del monitor (Touchpad).
 *    Se il touchpad non si puo' attivare, si ripiega sul pannello spento.
 *
 *  - parte ~2 s dopo che il display esterno fisico diventa un display desktop
 *    (se il telefono e' bloccato, aspetta lo sblocco);
 *  - il telefono torna normale quando il display esterno viene rimosso (cavo
 *    staccato), con Win+P, con tre dita sul touchpad o per una chiamata in arrivo;
 *  - una volta per collegamento: se l'utente blocca e risveglia il telefono,
 *    lo schermo torna acceso e resta acceso (via d'uscita).
 * Lo spegnimento automatico si puo' disattivare (Prefs.phoneOff); Win+P
 * (toggle) funziona comunque. Mai durante il mirroring: il monitor mostrerebbe
 * lo schermo nero del telefono.
 *
 * Tutto lo stato si cambia solo sul nostro thread (anche i tocchi del touchpad
 * arrivano qui, perche' la sua finestra e' aggiunta da questo thread).
 */
public final class PhoneScreen {

    static final int DELAY_MS = 2000;

    private static final int POWER_MODE_OFF    = 0;
    private static final int POWER_MODE_NORMAL = 2;

    private static ClassLoader cl;
    private static Handler handler;
    /** Telefono "spento" da noi: pannello spento, oppure nero con il touchpad. */
    private static volatile boolean turnedOff        = false;
    /** true = nero con il touchpad (pannello acceso); false = pannello spento. */
    private static volatile boolean touchpadMode     = false;
    private static volatile boolean pendingUnlock    = false;
    private static volatile boolean receiverRegistered = false;

    static void init(ClassLoader classLoader) { cl = classLoader; }

    /** ClassLoader di system_server (per le classi interne del framework). */
    static ClassLoader classLoader() { return cl; }

    /** Per la diagnostica e i riquadri: "normal", "off" o "touchpad". */
    static String state() { return !turnedOff ? "normal" : touchpadMode ? "touchpad" : "off"; }

    /**
     * Chiamato da ExternalDisplayGate quando compare un display esterno fisico.
     * Siamo dentro il lock del WindowManager: tutto il lavoro (registrazione del
     * receiver = lock di ActivityManager, lettura impostazioni) va sul nostro thread.
     */
    static void onExternalAdded() {
        handler().post(new Runnable() {
            @Override public void run() {
                Context c0 = systemContext();
                if (c0 != null) registerReceiver(c0);
            }
        });
        handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (Prefs.phoneOff()) tryTurnOff("collegamento");
                else XLog.i("[screen] spegnimento del telefono disattivato nelle impostazioni");
            }
        }, DELAY_MS);
    }

    /** Chiamato da ExternalDisplayGate quando il display esterno sparisce. */
    static void onExternalRemoved() {
        pendingUnlock = false;
        handler().post(new Runnable() {
            @Override public void run() { restore("cavo staccato"); }
        });
    }

    /** Win+P: telefono normale <-> spento (nel modo scelto nelle impostazioni). */
    static void toggle() {
        handler().post(new Runnable() {
            @Override public void run() {
                if (!ExternalDisplayGate.present || !isInteractive()) return;
                Context c0 = systemContext();
                if (c0 == null) return;
                registerReceiver(c0);   // per accorgersi di standby, risveglio e chiamate
                if (turnedOff) restore("Win+P");
                else turnOff(c0, "Win+P", Prefs.touchpad());
            }
        });
    }

    /**
     * Riquadro "Touchpad" nelle impostazioni rapide: on = il telefono diventa
     * subito il touchpad (se il monitor e' in desktop); off = telefono normale.
     */
    static void touchpadNow(final boolean on) {
        handler().post(new Runnable() {
            @Override public void run() {
                Context c0 = systemContext();
                if (c0 == null) return;
                registerReceiver(c0);
                if (!on) { if (touchpadMode) restore("riquadro touchpad"); return; }
                if (!ExternalDisplayGate.present || !isInteractive()) return;
                KeyguardManager km = c0.getSystemService(KeyguardManager.class);
                if (km != null && km.isKeyguardLocked()) { XLog.i("[screen] riquadro touchpad: telefono bloccato, ignoro"); return; }
                if (turnedOff && touchpadMode) return;
                if (turnedOff) restore("riquadro touchpad");   // da pannello spento a touchpad
                collapseShade(c0);
                turnOff(c0, "riquadro touchpad", true);
            }
        });
    }

    /** Il monitor e' passato a mirroring (riquadro): il telefono deve tornare visibile. */
    static void onMirroring() {
        handler().post(new Runnable() {
            @Override public void run() { restore("mirroring"); }
        });
    }

    /** Il monitor e' tornato in desktop senza essere ricollegato (riquadro). */
    static void onDesktopResumed() {
        handler().postDelayed(new Runnable() {
            @Override public void run() { if (Prefs.phoneOff()) tryTurnOff("ritorno al desktop"); }
        }, DELAY_MS);
    }

    /** Tre dita sul touchpad. */
    static void restoreFromTouchpad() {
        handler().post(new Runnable() {
            @Override public void run() { restore("tre dita sul touchpad"); }
        });
    }

    /** L'app ha cambiato le impostazioni: se il telefono e' spento nel modo sbagliato, cambia modo subito. */
    static void onSettingsChanged() {
        handler().post(new Runnable() {
            @Override public void run() {
                if (Touchpad.isShown()) Touchpad.reloadSettings();   // sensibilita'
                if (!turnedOff || Prefs.touchpad() == touchpadMode) return;
                Context c0 = systemContext();
                if (c0 == null) return;
                restore("impostazioni cambiate");
                if (!turnedOff && ExternalDisplayGate.present && isInteractive()) {
                    turnOff(c0, "impostazioni cambiate", Prefs.touchpad());
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // spegnimento / ripristino (solo sul nostro thread)
    // ------------------------------------------------------------------
    private static void tryTurnOff(String why) {
        if (!ExternalDisplayGate.present || turnedOff) return;
        Context ctx = systemContext();
        if (ctx == null) { XLog.i("[screen] nessun Context di sistema"); return; }
        if (!isInteractive()) { XLog.i("[screen] telefono in standby, non spengo"); return; }
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            pendingUnlock = true;
            registerReceiver(ctx);
            XLog.i("[screen] telefono bloccato: spengo lo schermo dopo lo sblocco");
            return;
        }
        turnOff(ctx, why, Prefs.touchpad());
    }

    private static void turnOff(Context ctx, String why, boolean useTouchpad) {
        if (turnedOff) return;
        if (mirroring(ctx)) { XLog.i("[screen] monitor in mirroring: il telefono resta acceso (" + why + ")"); return; }
        if (useTouchpad) {
            if (Touchpad.show(ctx)) {
                turnedOff = true;
                touchpadMode = true;
                XLog.i("[screen] schermo del telefono NERO con touchpad (" + why + ")");
                return;
            }
            XLog.i("[screen] touchpad non disponibile: spengo il pannello come al solito");
        }
        if (setInternalPower(POWER_MODE_OFF)) {
            turnedOff = true;
            touchpadMode = false;
            XLog.i("[screen] pannello telefono SPENTO (" + why + ")");
        }
    }

    private static void restore(String why) {
        if (!turnedOff) return;
        if (touchpadMode) {
            Touchpad.hide();
            XLog.i("[screen] telefono di nuovo normale (" + why + ")");
        } else if (!isInteractive()) {
            XLog.i("[screen] telefono in standby: la riaccensione la gestisce il sistema al risveglio");
        } else if (setInternalPower(POWER_MODE_NORMAL)) {
            XLog.i("[screen] pannello telefono RIACCESO (" + why + ")");
        } else {
            return;   // resta spento: Win+P riprova
        }
        turnedOff = false;
        touchpadMode = false;
    }

    /** Standby, risveglio, sblocco (per lo spegnimento rimandato) e chiamate in arrivo. */
    private static void registerReceiver(Context ctx) {
        if (receiverRegistered) return;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                        // standby (tasto di accensione o timeout): al risveglio il telefono e' normale
                        if (touchpadMode) {
                            Touchpad.hide();
                            touchpadMode = false;
                            turnedOff = false;
                            XLog.i("[screen] standby: touchpad chiuso");
                        }
                        return;
                    }
                    if (Intent.ACTION_SCREEN_ON.equals(a)) {
                        // il sistema ha riacceso lo schermo (risveglio): il nostro stato non vale piu'
                        if (touchpadMode) { Touchpad.hide(); touchpadMode = false; }
                        turnedOff = false;
                        return;
                    }
                    if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(a)) {
                        if (TelephonyManager.EXTRA_STATE_RINGING.equals(i.getStringExtra(TelephonyManager.EXTRA_STATE))) {
                            restore("chiamata in arrivo");
                        }
                        return;
                    }
                    if (pendingUnlock) {   // ACTION_USER_PRESENT
                        pendingUnlock = false;
                        handler().postDelayed(new Runnable() {
                            @Override public void run() { tryTurnOff("sblocco"); }
                        }, 800);
                        if (ExternalDisplayGate.present) handler().postDelayed(new Runnable() {
                            @Override public void run() { InputReconnect.nudge("sblocco"); }
                        }, 1200);
                    }
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            ctx.registerReceiver(r, f, null, handler(), Context.RECEIVER_EXPORTED);
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
            // priorita' "display": su questo thread arrivano i tocchi del touchpad
            HandlerThread t = new HandlerThread("HyperDesktop-screen", android.os.Process.THREAD_PRIORITY_DISPLAY);
            // In system_server un'eccezione non gestita su un thread ucciderebbe il
            // processo. Qui la fermiamo: chiudiamo il touchpad (il telefono torna
            // usabile) e il thread viene ricreato al bisogno.
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) {
                    try { XLog.e("[screen] eccezione non gestita sul thread, chiudo il touchpad", e); } catch (Throwable ignored) {}
                    try { Touchpad.hide(); } catch (Throwable ignored) {}
                    if (touchpadMode) { touchpadMode = false; turnedOff = false; }
                    receiverRegistered = false;   // il vecchio receiver usava il Looper morto
                    synchronized (PhoneScreen.class) { handler = null; }
                }
            });
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

    /** Secure.mirror_built_in_display == 1: il monitor ripete lo schermo del telefono. */
    private static boolean mirroring(Context ctx) {
        try { return Settings.Secure.getInt(ctx.getContentResolver(), SettingsGuardHooks.KEY_MIRROR, 0) == 1; }
        catch (Throwable t) { return false; }
    }

    private static void collapseShade(Context ctx) {
        try { XposedHelpers.callMethod(ctx.getSystemService("statusbar"), "collapsePanels"); }
        catch (Throwable t) { XLog.e("[screen] chiusura del pannello notifiche fallita", t); }
    }

    private static boolean isInteractive() {
        Context ctx = systemContext();
        if (ctx == null) return true;
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private PhoneScreen() {}
}
