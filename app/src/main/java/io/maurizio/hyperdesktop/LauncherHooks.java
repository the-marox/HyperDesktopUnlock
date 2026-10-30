/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.ContentResolver;
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
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;

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
 * (scelta Desktop) attende che il display esterno sia attivo E che il servizio di
 * accessibilita' del dock sia collegato, poi ci lancia sopra il desktop del dock
 * con ActivityOptions.setLaunchDisplayId.
 *
 * Perche' aspettare il servizio: aprire un'activity del dock sul monitor sposta
 * tutta l'app sulla configurazione del monitor, e se quel cambio raggiunge il
 * servizio di SmartDock mentre si avvia (prima di onServiceConnected) SmartDock
 * va in crash ("lateinit property context", DockService.onConfigurationChanged).
 * Succedeva al primo collegamento dopo un riavvio, con l'app che parte a freddo:
 * il desktop restava con il pulsante "Let's start" al centro, proprio sotto al
 * puntatore, e il primo clic apriva le impostazioni di SmartDock con il dialogo
 * dei permessi. Con il servizio gia' collegato il cambio arriva quando e' pronto.
 * Se il servizio non si collega entro SERVICE_TIMEOUT_MS il desktop si apre
 * comunque, per non lasciare il monitor vuoto.
 *
 * desktopIntent()/startOnDisplay()/launchExtras()/launchWhenReady() sono usati
 * anche da system_server (Win+D e il ritorno al desktop dal riquadro "Monitor").
 */
public final class LauncherHooks {

    private static final String VIEWMODEL =
        "com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel";

    /** Primo controllo dopo la scelta "Computer", poi uno ogni POLL_MS. */
    private static final long FIRST_CHECK_MS     = 1500;
    private static final long POLL_MS            = 500;
    /** Servizio appena collegato: margine perche' onServiceConnected finisca nell'app. */
    private static final long SETTLE_MS          = 1000;
    /** Oltre questo il desktop si apre comunque (servizio assente o che non parte). */
    private static final long SERVICE_TIMEOUT_MS = 15_000;
    /** Oltre questo, senza display esterno attivo, si rinuncia. */
    private static final long DISPLAY_TIMEOUT_MS = 12_000;

    private static long lastTrigger = 0;

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
        final Handler h = new Handler(Looper.getMainLooper());
        // le impostazioni si leggono fuori dal percorso della finestra
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    Context app = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    if (app != null) ensureDesktopSetting(app);
                } catch (Throwable t) { XLog.e("[launcher] scrittura di mirror_built_in_display = 0 fallita", t); }
                try {
                    if (!Prefs.autoLaunch()) { XLog.i("[launcher] avvio del dock disattivato nelle impostazioni"); return; }
                    Context ctx = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    if (ctx == null) { XLog.i("[launcher] nessun Context"); return; }
                    XLog.i("[launcher] scelta Desktop: programmo l'avvio di " + Prefs.launcherPkg());
                    launchWhenReady(ctx, h, false, FIRST_CHECK_MS, "scelta Desktop");
                } catch (Throwable t) { XLog.e("[launcher] avvio del dock fallito", t); }
            }
        });
    }

    /**
     * Scelta Computer: su alcune build (Xiaomi 14 houji, OS4.0.27, issue #2) SystemUI
     * applica la scelta ma non scrive mai mirror_built_in_display = 0 (lo Xiaomi 15 lo fa
     * da se'), e il monitor resta in mirroring. Lo scriviamo noi, come fa il riquadro
     * "Monitor"; la guardia in system_server impedisce poi che torni a 1.
     */
    private static void ensureDesktopSetting(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (Settings.Secure.getInt(cr, SettingsGuardHooks.KEY_MIRROR, 0) != 1) return;
        Settings.Secure.putInt(cr, SettingsGuardHooks.KEY_MIRROR, 0);
        XLog.i("[launcher] SystemUI non ha scritto " + SettingsGuardHooks.KEY_MIRROR + " = 0: lo scrivo io");
    }

    /**
     * Apre il desktop del dock sul monitor quando sono pronti sia il display esterno
     * sia il servizio di accessibilita' del dock (vedi la nota della classe), poi le
     * app extra. Tutto sul thread di h.
     * @param gate true in system_server (il display e' quello di ExternalDisplayGate),
     *             false in SystemUI (lo si cerca tra i display attivi).
     */
    static void launchWhenReady(final Context ctx, final Handler h, final boolean gate,
                                long firstDelayMs, final String why) {
        final long start = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() {
            private boolean settling = false;
            private boolean waitLogged = false;

            @Override public void run() {
                try {
                    long waited = SystemClock.uptimeMillis() - start;
                    int target = gate ? (ExternalDisplayGate.present ? ExternalDisplayGate.externalId : -1)
                                      : externalDisplayId(ctx);
                    if (target < 0) {
                        if (waited < DISPLAY_TIMEOUT_MS) { h.postDelayed(this, POLL_MS); return; }
                        XLog.i("[launcher] display esterno non attivo dopo " + waited / 1000 + " s: niente dock (" + why + ")");
                        return;
                    }
                    String pkg = Prefs.launcherPkg();
                    if (!dockServiceReady(ctx, pkg)) {
                        settling = false;
                        if (waited < SERVICE_TIMEOUT_MS) {
                            if (!waitLogged) {
                                waitLogged = true;
                                XLog.i("[launcher] aspetto il servizio di accessibilita' del dock prima di aprire il desktop");
                            }
                            h.postDelayed(this, POLL_MS);
                            return;
                        }
                        XLog.i("[launcher] servizio del dock non collegato dopo " + waited / 1000 + " s: apro il desktop comunque");
                    } else if (!settling) {
                        settling = true;               // appena collegato: lasciamo finire onServiceConnected
                        h.postDelayed(this, SETTLE_MS);
                        return;
                    }
                    launchNow(ctx, h, target, pkg, why);
                } catch (Throwable t) { XLog.e("[launcher] avvio del dock fallito", t); }
            }
        }, firstDelayMs);
    }

    private static void launchNow(Context ctx, Handler h, int target, String pkg, String why) {
        Intent i = desktopIntent(ctx, pkg);
        if (i == null) { XLog.i("[launcher] " + pkg + " non installato"); launchExtras(ctx, target, h); return; }
        startOnDisplay(ctx, i, target);
        XLog.i("[launcher] avvio di " + i.getComponent().flattenToShortString()
                + " richiesto sul display " + target + " (" + why + ")");
        launchExtras(ctx, target, h);
    }

    /**
     * Il servizio di accessibilita' del dock e' collegato? Stessa verifica che fa
     * SmartDock (getEnabledAccessibilityServiceList: i servizi gia' collegati).
     * true anche quando non c'e' niente da aspettare: il dock non ha un servizio,
     * oppure il servizio e' spento e il modulo non lo gestisce (nessuno lo accendera').
     */
    static boolean dockServiceReady(Context ctx, String pkg) {
        ComponentName svc = DockAccessibility.findService(ctx, pkg);
        if (svc == null) return true;
        if (!Prefs.manageA11y() && !DockAccessibility.enabledInSettings(ctx, svc)) return true;
        AccessibilityManager am = ctx.getSystemService(AccessibilityManager.class);
        if (am == null) return true;
        List<AccessibilityServiceInfo> on =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (on == null) return false;
        for (AccessibilityServiceInfo info : on) {
            ResolveInfo ri = info.getResolveInfo();
            if (ri != null && ri.serviceInfo != null
                    && svc.getPackageName().equals(ri.serviceInfo.packageName)
                    && svc.getClassName().equals(ri.serviceInfo.name)) return true;
        }
        return false;
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
