/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.view.WindowInsets;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Opzione (1.12.0, spenta di default): app sotto la barra di stato del monitor.
 *
 * Il report della 1.11.0 mostra che il monitor dichiara correttamente la sua barra
 * di stato (inset statusBars [0,0][3840,140], visibile), ma che le finestre delle
 * app sono "edge-to-edge" (fitInsetsTypes = 0, imposto da Android 15): e' l'app che
 * deve lasciare lo spazio in alto. Alcune (per esempio un browser con la barra delle
 * schede) sul monitor non lo fanno e la barra di stato copre il loro contenuto.
 *
 * Con l'opzione attiva, alle finestre principali delle app sul monitor aggiungiamo
 * statusBars ai tipi di inset da rispettare: e' il sistema a far iniziare la
 * finestra sotto la barra, qualunque cosa faccia l'app. Una barra nascosta (video a
 * schermo intero) non conta, quindi il video resta a tutto schermo.
 *
 * Punto di aggancio: DisplayPolicy.adjustWindowParamsLw, il metodo con cui il sistema
 * corregge i parametri di una finestra quando viene aggiunta o quando l'app li
 * cambia. Solo finestre TYPE_BASE_APPLICATION sul display esterno: la barra di stato
 * e le finestre di SystemUI non si toccano (modificare la barra stessa, in 1.10.8,
 * aveva mandato SystemUI in crash). Gira sotto il lock del WindowManager: legge solo
 * campi volatili, mai le impostazioni da file (vedi refresh()).
 */
final class MonitorInsets {

    /** Opzione attiva (aggiornata da refresh(), fuori dal lock). */
    static volatile boolean enabled;
    private static volatile String dockPkg = Prefs.D_LAUNCHER_PKG;
    private static volatile int logged;
    private static final String EXTRA = "hyperdesktop.fitStatusBar";

    static void hook(LoadPackageParam lp) {
        Class<?> dp = XposedHelpers.findClassIfExists("com.android.server.wm.DisplayPolicy", lp.classLoader);
        if (dp == null) { XLog.i("[insets] DisplayPolicy non trovata"); Diag.mark("monitor-insets", false); return; }
        int n = 0;
        for (Method m : dp.getDeclaredMethods()) {
            if (!m.getName().equals("adjustWindowParamsLw")) continue;
            Class<?>[] pt = m.getParameterTypes();
            // (WindowState finestra, LayoutParams parametri): la finestra prima dei parametri
            if (pt.length != 2 || pt[1] != WindowManager.LayoutParams.class || pt[0] == WindowManager.LayoutParams.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    /**
                     * AOSP, per TYPE_BASE_APPLICATION, lancia IllegalArgumentException se una
                     * finestra principale opaca a tutto schermo chiede di rispettare degli inset
                     * ("isn't translucent trying to fit insets"). Il nostro bit lo aggiungiamo
                     * DOPO quel controllo; qui lo togliamo prima, nel caso il sistema richiami
                     * il metodo con parametri gia' modificati da noi (cambio di display, ecc.).
                     */
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled) return;
                        try {
                            WindowManager.LayoutParams a = (WindowManager.LayoutParams) p.args[1];
                            if (!applies(p.args[0], a)) return;
                            p.setObjectExtra(EXTRA, Boolean.TRUE);
                            int sb = WindowInsets.Type.statusBars();
                            int fit = a.getFitInsetsTypes();
                            if ((fit & sb) != 0) a.setFitInsetsTypes(fit & ~sb);
                        } catch (Throwable ignored) {}
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.getObjectExtra(EXTRA) == null) return;
                        try { add((WindowManager.LayoutParams) p.args[1]); }
                        catch (Throwable ignored) {}
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[insets] hook adjustWindowParamsLw fallito", t); }
        }
        XLog.i("[insets] app sotto la barra di stato del monitor: " + n + " hook su adjustWindowParamsLw");
        Diag.mark("monitor-insets", n > 0);
    }

    /**
     * La finestra e' la principale di un'app sul display esterno (non il dock)?
     * Sotto il lock del WindowManager: solo confronti e una lettura del display.
     */
    private static boolean applies(Object win, WindowManager.LayoutParams a) {
        if (win == null || a == null || a.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return false;
        int ext = ExternalDisplayGate.externalId;
        if (ext <= 0 || !ExternalDisplayGate.present) return false;
        if (dockPkg.equals(a.packageName)) return false;   // il desktop del dock puo' stare sotto la barra
        return (Integer) XposedHelpers.callMethod(win, "getDisplayId") == ext;
    }

    /**
     * Dopo i controlli del sistema: la finestra rispetta la barra di stato. Serve sia il
     * TIPO (statusBars) sia il LATO (TOP): le app edge-to-edge azzerano anche i lati
     * (fitSides vuoto) e Android toglie lo spazio solo dai lati elencati (1.12.1; nella
     * 1.12.0 mancava il lato e la finestra restava a y=0).
     */
    private static void add(WindowManager.LayoutParams a) {
        int sb = WindowInsets.Type.statusBars();
        int top = WindowInsets.Side.TOP;
        int fit = a.getFitInsetsTypes();
        int sides = a.getFitInsetsSides();
        boolean changed = false;
        if ((fit & sb) == 0)    { a.setFitInsetsTypes(fit | sb);    changed = true; }
        if ((sides & top) == 0) { a.setFitInsetsSides(sides | top); changed = true; }
        if (changed && logged < 8) {
            logged++;
            XLog.i("[insets] " + a.packageName + ": finestra sotto la barra di stato del monitor");
        }
    }

    /** Rilegge l'opzione, fuori dal lock: al collegamento e quando l'app cambia le impostazioni. */
    static void refresh() {
        boolean on = Prefs.fitStatusBar();
        dockPkg = Prefs.launcherPkg();
        logged = 0;
        if (on != enabled) XLog.i("[insets] app sotto la barra di stato del monitor: " + (on ? "ATTIVO" : "spento"));
        enabled = on;
    }

    private MonitorInsets() {}
}
