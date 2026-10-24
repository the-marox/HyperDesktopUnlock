/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Avvio automatico del dock (SmartDock, o il pacchetto scelto nelle
 * impostazioni) sul display esterno quando si entra in modalita' desktop.
 *
 * Aggancia ConnectingDisplayViewModel.applyConnectionChoice(enableMirroring)
 * in SystemUI: viene attraversato sia dal tap su "Computer" nella finestra, sia
 * dal percorso automatico "non chiedermelo piu'". Quando enableMirroring == false
 * (scelta Desktop) attende che il display esterno sia attivo e ci lancia sopra
 * il desktop del dock con ActivityOptions.setLaunchDisplayId.
 *
 * desktopIntent()/startOnDisplay()/launchExtras() sono usati anche da system_server
 * (Win+D e il ritorno al desktop dal riquadro "Monitor").
 */
public final class LauncherHooks {

    private static final String VIEWMODEL =
        "com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel";
    private static final int[] RETRY_MS = { 1500, 3000, 5000, 8000, 12000 };

    private static long    lastTrigger = 0;
    private static boolean launched    = false;

    public static void hook(LoadPackageParam lp) {
        Class<?> vm = XposedHelpers.findClassIfExists(VIEWMODEL, lp.classLoader);
        if (vm == null) { XLog.i("[launcher] ViewModel non trovato"); Diag.mark("auto-launch", false); return; }

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
        XLog.i("[launcher] hook su " + n + " varianti di applyConnectionChoice");
        Diag.mark("auto-launch", n > 0);
    }

    private static void onDesktopChosen() {
        long now = SystemClock.uptimeMillis();
        if (now - lastTrigger < 10_000) return;   // applyConnectionChoice e' una suspend fun: chiamata 2 volte
        lastTrigger = now;
        launched = false;
        final Handler h = new Handler(Looper.getMainLooper());
        // le impostazioni si leggono fuori dal percorso della finestra
        h.post(new Runnable() {
            @Override public void run() {
                if (!Prefs.autoLaunch()) { XLog.i("[launcher] avvio del dock disattivato nelle impostazioni"); return; }
                XLog.i("[launcher] scelta Desktop: programmo l'avvio di " + Prefs.launcherPkg());
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
        });
    }

    /** @return true se ha lanciato (o se non ha senso riprovare). */
    private static boolean tryLaunch() {
        Context ctx = (Context) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        if (ctx == null) { XLog.i("[launcher] nessun Context"); return false; }

        int target = externalDisplayId(ctx);
        if (target < 0) { XLog.i("[launcher] display esterno non ancora attivo, riprovo"); return false; }

        String pkg = Prefs.launcherPkg();
        Intent i = desktopIntent(ctx, pkg);
        Handler main = new Handler(Looper.getMainLooper());
        if (i == null) { XLog.i("[launcher] " + pkg + " non installato"); launchExtras(ctx, target, main); return true; }
        startOnDisplay(ctx, i, target);
        XLog.i("[launcher] avvio di " + i.getComponent().flattenToShortString() + " richiesto sul display " + target);
        launchExtras(ctx, target, main);
        return true;
    }

    /**
     * App extra scelte nelle impostazioni, aperte sul monitor dopo il dock (una
     * volta per collegamento). Un breve ritardo le fa comparire sopra al dock.
     */
    static void launchExtras(final Context ctx, final int target, Handler h) {
        final String list = Prefs.extraApps();
        if (list.isEmpty()) return;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                PackageManager pm = ctx.getPackageManager();
                for (String raw : list.split("[,\\s]+")) {
                    String pkg = raw.trim();
                    if (pkg.isEmpty()) continue;
                    try {
                        Intent i = pm.getLaunchIntentForPackage(pkg);
                        if (i == null) { XLog.i("[launcher] app extra non installata: " + pkg); continue; }
                        startOnDisplay(ctx, i, target);
                        XLog.i("[launcher] app extra avviata sul display " + target + ": " + pkg);
                    } catch (Throwable t) { XLog.e("[launcher] app extra fallita: " + pkg, t); }
                }
            }
        }, 1200);
    }

    /**
     * Activity "desktop" del dock: prima SECONDARY_HOME (fatta apposta per i
     * display secondari, es. SmartDock SecondaryLauncherActivity), poi HOME, e
     * solo come ultima scelta il normale punto d'ingresso dell'app.
     * @return null se il pacchetto non e' installato.
     */
    static Intent desktopIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        for (String cat : new String[] {
                "android.intent.category.SECONDARY_HOME", Intent.CATEGORY_HOME }) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg);
            List<ResolveInfo> r = pm.queryIntentActivities(q, 0);
            if (r != null && !r.isEmpty()) {
                ActivityInfo ai = r.get(0).activityInfo;
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                i.setClassName(ai.packageName, ai.name);
                return i;
            }
        }
        return pm.getLaunchIntentForPackage(pkg);
    }

    /** Avvia l'activity sul display indicato, per l'utente corrente. */
    static void startOnDisplay(Context ctx, Intent i, int displayId) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        try {
            // SystemUI su HyperOS (e ovviamente system_server) gira come utente di
            // sistema: va indicato l'utente, altrimenti il lancio puo' finire
            // nell'utente/display sbagliato.
            Object current = XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT");
            XposedHelpers.callMethod(ctx, "startActivityAsUser", i, opts.toBundle(), current);
        } catch (Throwable t) {
            XLog.e("[launcher] startActivityAsUser fallito, ripiego su startActivity", t);
            ctx.startActivity(i, opts.toBundle());
        }
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
