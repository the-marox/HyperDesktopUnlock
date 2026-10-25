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
 * E' l'interruttore di tutte le funzioni "di sessione" (schermo del telefono,
 * servizio del dock, densita', tieni acceso, scorciatoie): valgono solo mentre
 * un monitor e' collegato, cosi' l'avvio del telefono senza monitor resta
 * identico a quello di serie.
 *
 * Gli hook girano con il lock globale del WindowManager: qui si aggiornano solo
 * i campi e si avvisano i gestori, che fanno il lavoro vero su thread propri.
 */
public final class ExternalDisplayGate {

    /** true mentre almeno un display esterno fisico e' collegato. */
    public static volatile boolean present = false;

    /** id logico del display esterno in modalita' desktop, -1 se assente. */
    public static volatile int externalId = -1;

    public static void hook(LoadPackageParam lp) {
        Class<?> dc = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayContent", lp.classLoader);
        if (dc == null) { XLog.i("[gate] DisplayContent non trovata"); Diag.mark("gate", false); return; }
        PhoneScreen.init(lp.classLoader);
        DockAccessibility.scheduleBootCheck();

        int ctors = XposedBridge.hookAllConstructors(dc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (isExternal(p.thisObject)) {
                    int id = displayIdOf(p.thisObject);
                    present = true;
                    externalId = id;
                    XLog.i("[gate] display esterno COLLEGATO (id " + id + ")");
                    DockAccessibility.onExternalAdded();
                    PhoneScreen.onExternalAdded();
                    DesktopSession.onExternalAdded(id);
                }
            }
        }).size();

        int n = 0;
        for (Method m : dc.getDeclaredMethods()) {
            if (!m.getName().equals("removeImmediately") && !m.getName().equals("remove")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (isExternal(p.thisObject) && present) {
                            present = false;
                            externalId = -1;
                            XLog.i("[gate] display esterno RIMOSSO");
                            PhoneScreen.onExternalRemoved();
                            DockAccessibility.onExternalRemoved();
                            DesktopSession.onExternalRemoved();
                            InputReconnect.onExternalRemoved();
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {}
        }
        XLog.i("[gate] hook installati (" + ctors + " costruttori + " + n + " metodi di rimozione)");
        Diag.mark("gate", ctors > 0 && n > 0);
        hookImePolicy(dc);
        hookFocusedApp(dc);
    }

    /**
     * Tastiera sul telefono: mentre il touchpad e' attivo il monitor risponde
     * "tastiera sul display principale" (FALLBACK_DISPLAY). Solo in memoria: non
     * tocca le impostazioni salvate, e smette appena il touchpad si chiude.
     * Gira sotto il lock del WindowManager: legge solo campi volatili.
     */
    private static void hookImePolicy(Class<?> dc) {
        int n = 0;
        for (Method m : dc.getDeclaredMethods()) {
            if (!m.getName().equals("getImePolicy") || m.getParameterTypes().length != 0) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!Touchpad.active || !Touchpad.keyboardOnPhone || !Touchpad.imeProbeOk || !Touchpad.softKeyboardWanted) return;
                        int id = externalId;
                        if (id > 0 && displayIdOf(p.thisObject) == id) {
                            p.setResult(1);   // WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                            if (imeLogged++ < 12) XLog.i("[ime] politica del monitor: tastiera sul telefono");
                        }
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[gate] hook getImePolicy fallito", t); }
        }
        int imms = hookImeDisplayChoice(dc.getClassLoader());
        XLog.i("[gate] tastiera sul telefono: " + n + " hook su getImePolicy, " + imms + " sulla scelta del servizio tastiere");
        Diag.mark("ime-on-phone", n + imms > 0);
    }

    /** Righe [ime] gia' scritte in questa sessione del touchpad (azzerato da Touchpad.show). */
    static volatile int imeLogged;

    /**
     * Il servizio delle tastiere (InputMethodManagerService) puo' decidere lo
     * schermo senza richiedere ogni volta la politica al window manager (la tiene
     * in memoria per display). Si aggancia il punto finale della scelta:
     * computeImeDisplayIdForTarget(displayId, ...) -> display su cui mostrarla.
     */
    private static int hookImeDisplayChoice(ClassLoader cl) {
        String[] classes = {
            "com.android.server.inputmethod.InputMethodManagerService",
            "com.android.server.inputmethod.InputMethodBindingController",
            "com.android.server.inputmethod.ImeVisibilityStateComputer",
            "com.android.server.inputmethod.InputMethodUtils",
        };
        int n = 0;
        for (String cn : classes) {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!m.getName().contains("computeImeDisplayId") || pt.length == 0 || pt[0] != int.class
                        || m.getReturnType() != int.class) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!Touchpad.active || !Touchpad.keyboardOnPhone || !Touchpad.imeProbeOk || !Touchpad.softKeyboardWanted) return;
                            int id = externalId;
                            if (id <= 0 || !(p.args[0] instanceof Integer) || (Integer) p.args[0] != id) return;
                            Object r = p.getResult();
                            if (r instanceof Integer && (Integer) r == 0) return;
                            p.setResult(0);   // Display.DEFAULT_DISPLAY: la tastiera va sul telefono
                            if (imeLogged++ < 12) XLog.i("[ime] tastiera del monitor (display " + id + ") spostata sul telefono (era " + r + ")");
                        }
                    });
                    n++;
                } catch (Throwable t) { XLog.e("[gate] hook " + m.getName() + " fallito", t); }
            }
        }
        return n;
    }

    /**
     * Ri-aggancio della tastiera: su display esterno la connessione d'input dell'app
     * nasce "morta" finche' un giro di focus non la rigenera. Qui notiamo quando
     * un'app prende il fuoco sul monitor, cosi' InputReconnect puo' rifare quel giro.
     * Gira sotto il lock del WindowManager: legge solo campi volatili e accoda il
     * lavoro vero su un altro thread.
     */
    private static void hookFocusedApp(Class<?> dc) {
        int n = 0;
        for (Method m : dc.getDeclaredMethods()) {
            if (!m.getName().equals("setFocusedApp") || m.getParameterTypes().length != 1) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length == 0 || p.args[0] == null) return;
                            int id = displayIdOf(p.thisObject);
                            if (id > 0 && id == externalId) InputReconnect.onAppFocused(id, p.args[0]);
                        } catch (Throwable ignored) {}
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[gate] hook setFocusedApp fallito", t); }
        }
        XLog.i("[gate] reconnect tastiera: " + n + " hook su setFocusedApp");
        Diag.mark("reconnect", n > 0);
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
