/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Alt+Tab sul monitor (system_server).
 *
 * Su HyperOS Alt+Tab e' gestito dal launcher di sistema, che vive solo sul
 * telefono: dal monitor non fa nulla. Qui lo intercettiamo all'ingresso
 * (InputManagerService.interceptKeyBeforeQueueing) SOLO se:
 *   - un display esterno fisico e' in modalita' desktop (ExternalDisplayGate),
 *   - il display con il focus non e' quello del telefono.
 * Altrimenti il tasto passa intatto.
 *
 * Comportamento, come lo scambio rapido di Windows (senza anteprime):
 *   Alt+Tab              -> app usata prima sul monitor
 *   Alt tenuto + Tab Tab -> risale le app aperte sul monitor, una alla volta
 *   Alt+Shift+Tab        -> nella direzione opposta
 * La lista viene "fotografata" alla prima pressione e resta fissa finche'
 * Alt non viene rilasciato.
 */
public final class AltTabHooks {

    static final boolean ENABLED = true;
    private static final int MAX_TASKS = 50;

    static ClassLoader cl;
    private static Object wmInternal;
    private static Handler handler;

    // stato della sessione: toccato solo dal thread handler
    private static List<Integer> session;
    private static int idx;

    // il DOWN di Tab che abbiamo consumato: consumiamo anche il relativo UP
    private static volatile boolean tabConsumed = false;

    public static void hook(LoadPackageParam lp) {
        if (!ENABLED) return;
        cl = lp.classLoader;
        int n = hookIn("com.android.server.input.InputManagerService");
        if (n == 0) n = hookIn("com.android.server.policy.PhoneWindowManager");
        XLog.i("[alttab] hook su " + n + " varianti di interceptKeyBeforeQueueing");
    }

    private static int hookIn(String cls) {
        Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
        if (c == null) return 0;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("interceptKeyBeforeQueueing") || m.getReturnType() != int.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        KeyEvent ev = null;
                        for (Object a : p.args) if (a instanceof KeyEvent) { ev = (KeyEvent) a; break; }
                        if (ev != null && onKey(ev)) p.setResult(0);   // 0 = non passare all'utente
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[alttab] hook fallito su " + cls, t); }
        }
        return n;
    }

    /** @return true se l'evento va consumato. Gira sul thread di input: deve essere veloce. */
    private static boolean onKey(KeyEvent ev) {
        if (DesktopShortcuts.onKey(ev)) return true;   // Stamp, Alt+F4, Win+P, Win+Maiusc+frecce/S
        int code = ev.getKeyCode();
        int action = ev.getAction();

        if (code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (action == KeyEvent.ACTION_UP) endSession();
            return false;   // Alt passa sempre
        }
        if (code != KeyEvent.KEYCODE_TAB) return false;

        if (action == KeyEvent.ACTION_UP) {
            if (tabConsumed) { tabConsumed = false; return true; }
            return false;
        }
        if (!ev.isAltPressed() || ev.isCtrlPressed() || ev.isMetaPressed()) return false;
        if (!ExternalDisplayGate.present) return false;

        final int focused = topFocusedDisplayId();
        if (focused <= 0) return false;   // focus sul telefono (o sconosciuto): lascia fare al sistema

        tabConsumed = true;
        if (ev.getRepeatCount() == 0) {
            final boolean back = ev.isShiftPressed();
            handler().post(new Runnable() { @Override public void run() { step(focused, back); } });
        }
        return true;
    }

    private static void endSession() {
        handler().post(new Runnable() { @Override public void run() { session = null; } });
    }

    // ------------------------------------------------------------------
    // logica di scambio (thread handler)
    // ------------------------------------------------------------------
    private static void step(int displayId, boolean back) {
        try {
            if (session == null) {
                session = tasksOn(displayId);
                idx = 0;
                XLog.i("[alttab] nuova sessione sul display " + displayId + ": " + session.size() + " app");
            }
            int n = session.size();
            if (n < 2) return;
            idx = back ? (idx - 1 + n) % n : (idx + 1) % n;
            moveToFront(session.get(idx));
        } catch (Throwable t) { XLog.e("[alttab] step fallito", t); }
    }

    /** Task sul display indicato, dal piu' recente; esclusi home/recenti e il launcher del dock. */
    static List<Integer> tasksOn(int displayId) {
        List<Integer> out = new ArrayList<>();
        Context ctx = systemContext();
        if (ctx == null) return out;
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(MAX_TASKS);
        if (tasks == null) return out;
        for (ActivityManager.RunningTaskInfo t : tasks) {
            if (intField(t, "displayId", -1) != displayId) continue;
            int type = activityType(t);
            if (type == 2 /* HOME */ || type == 3 /* RECENTS */) continue;
            ComponentName top = t.topActivity != null ? t.topActivity : t.baseActivity;
            if (top != null && LauncherHooks.LAUNCHER_PACKAGE.equals(top.getPackageName())) continue;
            out.add(t.taskId);
        }
        return out;
    }

    private static void moveToFront(int taskId) {
        Context ctx = systemContext();
        if (ctx == null) return;
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        am.moveTaskToFront(taskId, 0);
    }

    // ------------------------------------------------------------------
    // utilita'
    // ------------------------------------------------------------------
    static int topFocusedDisplayId() {
        try {
            if (wmInternal == null) {
                Class<?> ls  = XposedHelpers.findClass("com.android.server.LocalServices", cl);
                Class<?> wmi = XposedHelpers.findClass("com.android.server.wm.WindowManagerInternal", cl);
                wmInternal = XposedHelpers.callStaticMethod(ls, "getService", wmi);
            }
            return (Integer) XposedHelpers.callMethod(wmInternal, "getTopFocusedDisplayId");
        } catch (Throwable t) {
            XLog.e("[alttab] getTopFocusedDisplayId fallito", t);
            return -1;
        }
    }

    private static int activityType(Object taskInfo) {
        int v = intField(taskInfo, "topActivityType", -1);
        if (v >= 0) return v;
        try { return (Integer) XposedHelpers.callMethod(taskInfo, "getActivityType"); }
        catch (Throwable t) { return -1; }
    }

    private static int intField(Object o, String f, int def) {
        try { return XposedHelpers.getIntField(o, f); } catch (Throwable t) { return def; }
    }

    static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-alttab");
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) { return null; }
    }

    private AltTabHooks() {}
}
