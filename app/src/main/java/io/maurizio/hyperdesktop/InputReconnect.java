/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/**
 * Ri-aggancio della tastiera sul monitor (1.10.12).
 *
 * Su display esterno la connessione d'input dell'app nasce "morta": i primi tasti
 * fisici non arrivano al campo di testo finche' un giro completo di focus-finestra
 * (blocco+sblocco schermo, spostamento telefono->monitor, clic fuori/dentro) non la
 * rigenera. I dump confermano che al momento del guasto lo stato del framework e'
 * gia' corretto (display a fuoco = monitor, finestra a fuoco = l'app, IME legato al
 * client dell'app): il pezzo "morto" e' la InputConnection lato app, che solo un
 * nuovo giro di focus ricrea (ViewRootImpl.onWindowFocusChanged true -> restartInput).
 *
 * Qui replichiamo quel giro una volta, da soli: togliamo il focus all'app sul display
 * esterno e, dopo una breve pausa, glielo ridiamo. In mezzo la finestra riceve
 * focus=false poi focus=true, e l'app rifa' la connessione. Tutto sotto il lock del
 * WindowManager, sul thread di AltTabHooks (mai dentro un hook), con guardie e
 * try/catch: un errore non puo' propagarsi, e c'e' una rete che non lascia mai il
 * display senza finestra a fuoco. Disattivabile dall'app (impostazione "reconnect").
 *
 * Metodi usati (firme reali lette dal probe 1.10.11 su questo framework):
 *   RootWindowContainer#getDisplayContent(int)
 *   DisplayContent#setFocusedApp(ActivityRecord)
 *   WindowManagerService#updateFocusedWindowLocked(int, boolean)   // modo 0 = UPDATE_FOCUS_NORMAL
 */
public final class InputReconnect {

    private static final long STEP_GAP_MS = 250;   // pausa tra "togli focus" e "ridai focus"
    private static final int  UPDATE_FOCUS_NORMAL = 0;

    private static volatile boolean busy;

    // riflessione risolta una volta sola
    private static volatile Object wms, lock, root;
    private static volatile Method mGetDc, mSetFocusedApp, mUpdateFocus;
    private static volatile Object savedApp;

    /**
     * Rifa' il giro di focus sull'app a fuoco del display esterno. Chiamabile da
     * qualsiasi thread: il lavoro vero gira sul thread di AltTabHooks.
     */
    static void nudge(final String why) {
        try {
            if (!Prefs.imeReconnect()) return;
            if (!ExternalDisplayGate.present) return;
            final int displayId = ExternalDisplayGate.externalId;
            if (displayId <= 0) return;
            if (busy) return;
            busy = true;
            AltTabHooks.handler().post(new Runnable() {
                @Override public void run() { phaseOut(displayId); }
            });
            AltTabHooks.handler().postDelayed(new Runnable() {
                @Override public void run() { phaseIn(displayId, why); }
            }, STEP_GAP_MS);
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
            }
            XLog.i("[reconnect] giro di focus eseguito sul display " + displayId + " (" + why + ")");
        } catch (Throwable t) {
            XLog.e("[reconnect] fase 'ridai focus' fallita, provo a ripristinare", t);
            try { synchronized (lock) {
                Object dc = mGetDc.invoke(root, displayId);
                if (dc != null && savedApp != null) {
                    mSetFocusedApp.invoke(dc, new Object[]{ savedApp });
                    mUpdateFocus.invoke(wms, new Object[]{ UPDATE_FOCUS_NORMAL, true });
                }
            }} catch (Throwable ignored) {}
        } finally { savedApp = null; busy = false; }
    }

    private InputReconnect() {}
}
