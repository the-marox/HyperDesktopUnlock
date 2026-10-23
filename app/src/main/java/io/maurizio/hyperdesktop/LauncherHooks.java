/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Avvio automatico di un launcher (SmartDock) sul display esterno quando si
 * entra in modalita' desktop.
 *
 * Aggancia ConnectingDisplayViewModel.applyConnectionChoice(enableMirroring):
 * viene attraversato sia dal tap su "Computer" nella finestra, sia dal percorso
 * automatico "non chiedermelo piu'". Quando enableMirroring == false (scelta
 * Desktop) attende che il display esterno sia attivo e ci lancia sopra il
 * launcher con ActivityOptions.setLaunchDisplayId.
 */
public final class LauncherHooks {

    static final boolean AUTO_LAUNCH      = true;
    static final String  LAUNCHER_PACKAGE = "cu.axel.smartdock";

    private static final String VIEWMODEL =
        "com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel";
    private static final int[] RETRY_MS = { 1500, 3000, 5000, 8000, 12000 };

    private static long    lastTrigger = 0;
    private static boolean launched    = false;

    public static void hook(LoadPackageParam lp) {
        if (!AUTO_LAUNCH) return;
        Class<?> vm = XposedHelpers.findClassIfExists(VIEWMODEL, lp.classLoader);
        if (vm == null) { XLog.i("[launcher] ViewModel non trovato"); return; }

        int n = 0;
        for (Method m : vm.getDeclaredMethods()) {
            if (!m.getName().contains("applyConnectionChoice")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Boolean enableMirroring = null;
                        for (Object a : p.args) if (a instanceof Boolean) { enableMirroring = (Boolean) a; break; }
                        if (Boolean.FALSE.equals(enableMirroring)) onDesktopChosen();
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[launcher] hook fallito", t); }
        }
        XLog.i("[launcher] hook su " + n + " varianti di applyConnectionChoice; launcher=" + LAUNCHER_PACKAGE);
    }

    private static void onDesktopChosen() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTrigger < 10_000) return;   // applyConnectionChoice e' una suspend fun: chiamata 2 volte
        lastTrigger = now;
        launched = false;
        XLog.i("[launcher] scelta Desktop: programmo l'avvio di " + LAUNCHER_PACKAGE);
        Handler h = new Handler(Looper.getMainLooper());
        for (final int ms : RETRY_MS) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (launched) return;
                    try { if (tryLaunch()) launched = true; }
                    catch (Throwable t) { XLog.e("[launcher] avvio fallito", t); }
                }
            }, ms);
        }
    }

    /** @return true se ha lanciato (o se non ha senso riprovare). */
    private static boolean tryLaunch() {
        Context ctx = (Context) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        if (ctx == null) { XLog.i("[launcher] nessun Context"); return false; }

        int target = externalDisplayId(ctx);
        if (target < 0) { XLog.i("[launcher] display esterno non ancora attivo, riprovo"); return false; }

        Intent i = desktopIntent(ctx);
        if (i == null) { XLog.i("[launcher] " + LAUNCHER_PACKAGE + " non installato"); return true; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(target);
        XLog.i("[launcher] avvio " + i.getComponent() + " sul display " + target);
        try {
            // SystemUI su HyperOS gira come processo di sistema: va indicato l'utente,
            // altrimenti il lancio puo' finire nell'utente/display sbagliato.
            Object current = XposedHelpers.getStaticObjectField(android.os.UserHandle.class, "CURRENT");
            XposedHelpers.callMethod(ctx, "startActivityAsUser", i, opts.toBundle(), current);
        } catch (Throwable t) {
            XLog.e("[launcher] startActivityAsUser fallito, ripiego su startActivity", t);
            ctx.startActivity(i, opts.toBundle());
        }
        XLog.i("[launcher] " + LAUNCHER_PACKAGE + " avviato sul display " + target);
        return true;
    }

    /**
     * Activity "desktop" del launcher: prima SECONDARY_HOME (fatta apposta per i
     * display secondari, es. SmartDock SecondaryLauncherActivity), poi HOME, e
     * solo come ultima scelta il normale punto d'ingresso dell'app.
     */
    private static Intent desktopIntent(Context ctx) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String cat : new String[] {
                "android.intent.category.SECONDARY_HOME", Intent.CATEGORY_HOME }) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(LAUNCHER_PACKAGE);
            java.util.List<android.content.pm.ResolveInfo> r = pm.queryIntentActivities(q, 0);
            if (r != null && !r.isEmpty()) {
                android.content.pm.ActivityInfo ai = r.get(0).activityInfo;
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                i.setClassName(ai.packageName, ai.name);
                XLog.i("[launcher] activity desktop (" + cat.substring(cat.lastIndexOf('.') + 1) + "): " + ai.name);
                return i;
            }
        }
        return pm.getLaunchIntentForPackage(LAUNCHER_PACKAGE);
    }

    private static int externalDisplayId(Context ctx) {
        DisplayManager dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return -1;
        int fallback = -1;
        for (Display d : dm.getDisplays()) {
            int id = d.getDisplayId();
            if (id == Display.DEFAULT_DISPLAY) continue;
            if (d.getState() == Display.STATE_OFF) continue;
            try {
                int type = (Integer) XposedHelpers.callMethod(d, "getType");  // @hide
                if (type == 2 /* TYPE_EXTERNAL */) return id;
            } catch (Throwable ignored) {}
            if (fallback < 0) fallback = id;
        }
        return fallback;
    }

    private LauncherHooks() {}
}
