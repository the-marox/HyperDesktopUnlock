/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
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
 * Comportamento, come lo scambio rapido di Windows:
 *   Alt+Tab              -> app usata prima sul monitor
 *   Alt tenuto + Tab Tab -> risale le app aperte sul monitor, una alla volta
 *   Alt+Shift+Tab        -> nella direzione opposta
 * La lista viene "fotografata" alla prima pressione e resta fissa finche'
 * Alt non viene rilasciato.
 *
 * Con le anteprime attive (Prefs.switcherPreview, SwitcherOverlay) tenendo Alt
 * compare la barra delle app e lo scambio avviene al rilascio di Alt; se la barra
 * non si puo' mostrare (o l'opzione e' spenta) si ripiega sullo scambio immediato
 * a ogni Tab, come prima.
 */
public final class AltTabHooks {

    private static final int MAX_TASKS = 50;

    static ClassLoader cl;
    private static Object wmInternal;
    private static Handler handler;

    /** Una app sul monitor: id del task + etichetta e icona per l'anteprima. */
    static final class Task {
        final int id; final CharSequence label; final Drawable icon;
        Task(int id, CharSequence label, Drawable icon) { this.id = id; this.label = label; this.icon = icon; }
    }

    // stato della sessione: toccato solo dal thread handler
    private static List<Task> session;
    private static int idx;
    private static boolean previewing;

    // il DOWN di Tab che abbiamo consumato: consumiamo anche il relativo UP
    private static volatile boolean tabConsumed = false;

    public static void hook(LoadPackageParam lp) {
        cl = lp.classLoader;
        int n = hookIn("com.android.server.input.InputManagerService");
        if (n == 0) n = hookIn("com.android.server.policy.PhoneWindowManager");
        XLog.i("[alttab] hook su " + n + " varianti di interceptKeyBeforeQueueing");
        Diag.mark("keys", n > 0);
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

    /**
     * @return true se l'evento va consumato. Gira sul thread di input: deve essere veloce.
     * Le impostazioni si consultano solo sulla pressione che avvierebbe un'azione;
     * i rilasci dei tasti gia' consumati vengono consumati comunque.
     */
    private static boolean onKey(KeyEvent ev) {
        if (DesktopShortcuts.onKey(ev)) return true;   // Stamp, Alt+F4, Win+D/P, Win+Maiusc+frecce/S
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
        if (!tabConsumed && !Prefs.shortcuts()) return false;   // tasto tenuto premuto: gia' deciso

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
        handler().post(new Runnable() { @Override public void run() { commitAndClear(); } });
    }

    /** Rilascio di Alt: con l'anteprima, l'app evidenziata va in primo piano ora. */
    private static void commitAndClear() {
        try {
            if (previewing) {
                SwitcherOverlay.hide();
                if (session != null && idx >= 0 && idx < session.size()) moveToFront(session.get(idx).id);
            }
        } catch (Throwable t) { XLog.e("[alttab] commit fallito", t); }
        session = null;
        previewing = false;
    }

    // ------------------------------------------------------------------
    // logica di scambio (thread handler)
    // ------------------------------------------------------------------
    private static void step(int displayId, boolean back) {
        try {
            if (session == null) {
                session = detailedTasksOn(displayId);
                int n = session.size();
                XLog.i("[alttab] nuova sessione sul display " + displayId + ": " + n + " app");
                if (n < 2) { idx = 0; previewing = false; return; }
                idx = back ? n - 1 : 1;   // come Windows: parte dall'app precedente
                previewing = Prefs.switcherPreview() && SwitcherOverlay.show(displayId, session, idx);
                if (!previewing) moveToFront(session.get(idx).id);   // scambio immediato (anteprima off o non riuscita)
                return;
            }
            int n = session.size();
            if (n < 2) return;
            idx = back ? (idx - 1 + n) % n : (idx + 1) % n;
            if (previewing) SwitcherOverlay.updateSelection(idx);
            else moveToFront(session.get(idx).id);
        } catch (Throwable t) { XLog.e("[alttab] step fallito", t); }
    }

    /** Task sul display indicato, dal piu' recente; esclusi home/recenti e il dock. */
    static List<Integer> tasksOn(int displayId) {
        List<Integer> out = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo t : switchableTasks(displayId)) out.add(t.taskId);
        return out;
    }

    /** Come tasksOn, ma con etichetta e icona per l'anteprima (thread handler, non input). */
    private static List<Task> detailedTasksOn(int displayId) {
        List<Task> out = new ArrayList<>();
        Context ctx = systemContext();
        PackageManager pm = ctx != null ? ctx.getPackageManager() : null;
        for (ActivityManager.RunningTaskInfo t : switchableTasks(displayId)) {
            ComponentName top = t.topActivity != null ? t.topActivity : t.baseActivity;
            CharSequence label = null; Drawable icon = null;
            if (pm != null && top != null) {
                try { icon = pm.getActivityIcon(top); } catch (Throwable ignored) {}
                try { label = pm.getActivityInfo(top, 0).loadLabel(pm); } catch (Throwable ignored) {}
                if (label == null) label = top.getPackageName();
            }
            out.add(new Task(t.taskId, label, icon));
        }
        return out;
    }

    /** Filtro comune: task scambiabili sul display, dal piu' recente. */
    private static List<ActivityManager.RunningTaskInfo> switchableTasks(int displayId) {
        List<ActivityManager.RunningTaskInfo> out = new ArrayList<>();
        String dock = Prefs.launcherPkg();
        for (ActivityManager.RunningTaskInfo t : runningTasks()) {
            if (intField(t, "displayId", -1) != displayId) continue;
            int type = activityType(t);
            if (type == 2 /* HOME */ || type == 3 /* RECENTS */) continue;
            ComponentName top = t.topActivity != null ? t.topActivity : t.baseActivity;
            if (top != null && dock.equals(top.getPackageName())) continue;
            out.add(t);
        }
        return out;
    }

    /** Task del desktop del dock (activity indicata) sul display, -1 se non c'e'. */
    static int taskOf(int displayId, ComponentName activity) {
        if (activity == null) return -1;
        for (ActivityManager.RunningTaskInfo t : runningTasks()) {
            if (intField(t, "displayId", -1) != displayId) continue;
            if (activity.equals(t.baseActivity) || activity.equals(t.topActivity)) return t.taskId;
        }
        return -1;
    }

    private static List<ActivityManager.RunningTaskInfo> runningTasks() {
        Context ctx = systemContext();
        if (ctx == null) return new ArrayList<>();
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(MAX_TASKS);
        return tasks != null ? tasks : new ArrayList<ActivityManager.RunningTaskInfo>();
    }

    static void moveToFront(int taskId) {
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
            // In system_server un'eccezione non gestita su un thread ucciderebbe il processo
            // (riavvio del telefono). Qui la fermiamo: la sessione Alt+Tab si chiude, il
            // ricevitore di DesktopSession (che usava questo Looper) si registra di nuovo e il
            // thread viene ricreato al prossimo uso.
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) {
                    try { XLog.e("[alttab] eccezione non gestita sul thread, lo ricreo", e); } catch (Throwable ignored) {}
                    try { if (previewing) SwitcherOverlay.hide(); } catch (Throwable ignored) {}
                    session = null; previewing = false; tabConsumed = false;
                    synchronized (AltTabHooks.class) { handler = null; }
                    try { DesktopSession.onHandlerLost(); } catch (Throwable ignored) {}
                }
            });
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
