/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * DIAGNOSTICA (introdotta in 1.10.11). Non cambia il comportamento del modulo:
 * si limita a stampare UNA volta, all'avvio di system_server, i nomi e le firme
 * reali dei metodi di "focus delle finestre" e di "(ri)avvio dell'input" presenti
 * nel framework in uso (HyperOS / Android di questo dispositivo).
 *
 * Serve a mirare il vero intervento (1.10.12): su display esterno la connessione
 * d'input dell'app nasce "morta" e solo un nuovo giro di focus (sblocco schermo,
 * spostamento telefono->monitor, clic fuori/dentro) la rigenera. Per replicarlo
 * dal framework servono le firme esatte di queste funzioni, che variano da una
 * versione all'altra: qui le leggiamo invece di indovinarle.
 *
 * Nessuna chiamata che modifica lo stato: solo riflessione in lettura e log.
 * Le righe hanno prefisso [reconnect-probe]; si recuperano con:
 *   su -c 'logcat -d -s HyperDesktop' | grep reconnect-probe
 */
public final class InputReconnect {

    private static volatile boolean probed;

    /** Dove vivono le funzioni di focus-finestra e di (ri)avvio dell'input. */
    private static final String[] CLASSES = {
        "com.android.server.inputmethod.InputMethodManagerService",
        "com.android.server.inputmethod.InputMethodManagerInternal",
        "com.android.server.wm.WindowManagerService",
        "com.android.server.wm.RootWindowContainer",
        "com.android.server.wm.DisplayContent",
    };

    /** Parole chiave (minuscolo) nei nomi dei metodi che ci interessano. */
    private static final String[] KEYS = {
        "startinput", "restartinput", "finishinput", "schedulerestart",
        "resetcurrent", "clearclient", "unbind", "rebind",
        "computeimetarget", "updateinputmethodtarget", "onimeparent",
        "reportfocus", "setfocusedapp", "updatefocused", "assignfocus",
        "movefocus", "onpointerdownoutsidefocus", "gainedfocus",
    };

    private static final int MAX_PER_CLASS = 30;

    /** Elenca una volta, nel log, le firme reali dei metodi candidati sul framework in uso. */
    public static void probe(ClassLoader cl) {
        if (probed) return;
        probed = true;
        int total = 0;
        XLog.i("[reconnect-probe] inventario funzioni focus/input (SDK " + Build.VERSION.SDK_INT + ")");
        for (String cn : CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            if (c == null) { XLog.i("[reconnect-probe] " + simple(cn) + ": classe assente"); continue; }
            int n = 0;
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!matches(m.getName().toLowerCase(Locale.US))) continue;
                    if (n >= MAX_PER_CLASS) { XLog.i("[reconnect-probe] " + simple(cn) + ": ...troncato"); break; }
                    XLog.i("[reconnect-probe] " + simple(cn) + "#" + sig(m));
                    n++; total++;
                }
            } catch (Throwable t) { XLog.e("[reconnect-probe] " + simple(cn) + " non leggibile", t); }
            if (n == 0) XLog.i("[reconnect-probe] " + simple(cn) + ": nessun metodo corrispondente");
        }
        XLog.i("[reconnect-probe] fine (" + total + " metodi)");
        Diag.mark("reconnect-probe", total > 0);
    }

    private static boolean matches(String lowerName) {
        for (String k : KEYS) if (lowerName.contains(k)) return true;
        return false;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pt[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private InputReconnect() {}
}
