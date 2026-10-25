/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * Ri-aggancio della tastiera sul monitor (1.10.12, innesco per-app dalla 1.10.13).
 *
 * Su display esterno la connessione d'input dell'app nasce "morta": i primi tasti
 * fisici non arrivano al campo di testo finche' un giro completo di focus-finestra
 * (blocco+sblocco schermo, spostamento telefono->monitor, clic fuori/dentro) non la
 * rigenera. I dump confermano che al momento del guasto lo stato del framework e'
 * gia' corretto (display a fuoco = monitor, finestra a fuoco = l'app, IME legato al
 * client dell'app): il pezzo "morto" e' la InputConnection lato app, che solo un
 * nuovo giro di focus ricrea (ViewRootImpl.onWindowFocusChanged true -> restartInput).
 *
 * Qui replichiamo quel giro: quando un'app prende il fuoco sul monitor (hook su
 * DisplayContent.setFocusedApp in ExternalDisplayGate) le togliamo e, dopo una breve
 * pausa, le ridiamo il focus. Ogni app viene ri-agganciata una sola volta per
 * sessione di collegamento (vedi DONE), per non sfarfallare a ogni cambio app.
 *
 * Tutto sotto il lock del WindowManager, sul thread di AltTabHooks (mai dentro un
 * hook), con guardie e try/catch: un errore non puo' propagarsi, e c'e' una rete che
 * non lascia mai il display senza finestra a fuoco. Disattivabile dall'app.
 *
 * Firme reali lette dal probe 1.10.11 su questo framework (Android 17 / SDK 37):
 *   RootWindowContainer#getDisplayContent(int)
 *   DisplayContent#setFocusedApp(ActivityRecord)
 *   WindowManagerService#updateFocusedWindowLocked(int, boolean)   // modo 0 = UPDATE_FOCUS_NORMAL
 */
public final class InputReconnect {

    private static final long STEP_GAP_MS       = 250;   // pausa tra "togli focus" e "ridai focus"
    private static final long APP_FOCUS_DELAY_MS = 400;  // l'app si assesta prima del giro
    private static final int  UPDATE_FOCUS_NORMAL = 0;

    private static volatile boolean busy;

    // riflessione risolta una volta sola
    private static volatile Object wms, lock, root;
    private static volatile Method mGetDc, mSetFocusedApp, mUpdateFocus;
    private static volatile Object savedApp;

    /** App gia' ri-agganciate in questa sessione di collegamento (una volta ciascuna). */
    private static final WeakHashMap<Object, Boolean> DONE = new WeakHashMap<>();

    /** Un'app ha preso il fuoco sul monitor (dall'hook su setFocusedApp). */
    static void onAppFocused(int displayId, Object app) {
        try {
            if (busy || app == null) return;
            if (!Prefs.imeReconnect()) return;
            if (!ExternalDisplayGate.present || displayId != ExternalDisplayGate.externalId) return;
            synchronized (DONE) { if (DONE.containsKey(app)) return; DONE.put(app, Boolean.TRUE); }
            nudge("app a fuoco", APP_FOCUS_DELAY_MS);
        } catch (Throwable t) { XLog.e("[reconnect] onAppFocused fallito", t); }
    }

    /** Monitor scollegato: le app andranno ri-agganciate al prossimo collegamento. */
    static void onExternalRemoved() {
        synchronized (DONE) { DONE.clear(); }
        busy = false;
    }

    /** Rifa' il giro di focus (togli+ridai) sull'app a fuoco del monitor, dopo startDelay ms. */
    static void nudge(final String why, long startDelay) {
        try {
            if (!Prefs.imeReconnect() || !ExternalDisplayGate.present) return;
            final int displayId = ExternalDisplayGate.externalId;
            if (displayId <= 0 || busy) return;
            busy = true;
            AltTabHooks.handler().postDelayed(new Runnable() {
                @Override public void run() { phaseOut(displayId); }
            }, startDelay);
            AltTabHooks.handler().postDelayed(new Runnable() {
                @Override public void run() { phaseIn(displayId, why); }
            }, startDelay + STEP_GAP_MS);
        } catch (Throwable t) { busy = false; XLog.e("[reconnect] avvio fallito", t); }
    }

    private static boolean resolve() {
        if (mUpdateFocus != null) return true;
        try {
            Object w = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "getWindowManagerService");
            Object lk = XposedHelpers.getObjectField(w, "mGlobalLock");
            Object rt = XposedHelpers.getObjectField(w, "mRoot");
            ClassLoader cl = w.getClass().getClassLoader();
            Class<?> ar  = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl);
            Class<?> dcC = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            Method getDc = XposedHelpers.findMethodExact(rt.getClass(), "getDisplayContent", int.class);
            Method setFa = XposedHelpers.findMethodExact(dcC, "setFocusedApp", ar);
            Method upd   = XposedHelpers.findMethodExact(w.getClass(), "updateFocusedWindowLocked", int.class, boolean.class);
            getDc.setAccessible(true); setFa.setAccessible(true); upd.setAccessible(true);
            wms = w; lock = lk; root = rt; mGetDc = getDc; mSetFocusedApp = setFa; mUpdateFocus = upd;
            return true;
        } catch (Throwable t) { XLog.e("[reconnect] riflessione non disponibile, funzione inattiva", t); return false; }
    }

    /** Toglie il focus all'app sul display esterno (focus=false in arrivo all'app). */
    private static void phaseOut(int displayId) {
        savedApp = null;
        try {
            if (!resolve()) { busy = false; return; }
            synchronized (lock) {
                Object dc = mGetDc.invoke(root, displayId);
                if (dc == null) { XLog.i("[reconnect] display " + displayId + " assente, salto"); busy = false; return; }
                Object app = XposedHelpers.getObjectField(dc, "mFocusedApp");
                if (app == null) { XLog.i("[reconnect] nessuna app a fuoco sul display " + displayId + ", salto"); busy = false; return; }
                savedApp = app;
                mSetFocusedApp.invoke(dc, new Object[]{ null });
                mUpdateFocus.invoke(wms, new Object[]{ UPDATE_FOCUS_NORMAL, true });
                XLog.i("[reconnect] focus tolto (display " + displayId + "), finestra a fuoco ora = " + focusName(dc));
            }
        } catch (Throwable t) { XLog.e("[reconnect] fase 'togli focus' fallita", t); savedApp = null; busy = false; }
    }

    /** Ridai il focus all'app (focus=true: l'app rifa' la connessione d'input). */
    private static void phaseIn(int displayId, String why) {
        try {
            if (savedApp == null || mUpdateFocus == null) return;   // niente da fare (busy azzerato nel finally)
            synchronized (lock) {
                Object dc = mGetDc.invoke(root, displayId);
                if (dc == null) return;
                mSetFocusedApp.invoke(dc, new Object[]{ savedApp });
                mUpdateFocus.invoke(wms, new Object[]{ UPDATE_FOCUS_NORMAL, true });
                // rete di sicurezza: non lasciare MAI il display senza finestra a fuoco
                Object now = XposedHelpers.getObjectField(dc, "mFocusedApp");
                if (now == null) {
                    mSetFocusedApp.invoke(dc, new Object[]{ savedApp });
                    mUpdateFocus.invoke(wms, new Object[]{ UPDATE_FOCUS_NORMAL, true });
                }
                XLog.i("[reconnect] focus ridato (display " + displayId + ", " + why + "), finestra a fuoco = " + focusName(dc));
            }
        } catch (Throwable t) {
            XLog.e("[reconnect] fase 'ridai focus' fallita, ripristino", t);
            try { synchronized (lock) {
                Object dc = mGetDc.invoke(root, displayId);
                if (dc != null && savedApp != null) {
                    mSetFocusedApp.invoke(dc, new Object[]{ savedApp });
                    mUpdateFocus.invoke(wms, new Object[]{ UPDATE_FOCUS_NORMAL, true });
                }
            }} catch (Throwable ignored) {}
        } finally { savedApp = null; busy = false; }
    }

    private static String focusName(Object dc) {
        try { Object w = XposedHelpers.getObjectField(dc, "mCurrentFocus"); return w == null ? "null" : String.valueOf(w); }
        catch (Throwable t) { return "?"; }
    }

    private InputReconnect() {}
}
