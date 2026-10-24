/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Comandi dall'app del modulo (riquadri delle impostazioni rapide, schermata
 * Diagnostica) verso system_server e SystemUI.
 *
 * L'app manda un broadcast ORDINATO con Prefs.ACTION_COMMAND e l'extra "cmd". Solo
 * chi ha il permesso con firma del modulo puo' mandarlo (i ricevitori lo esigono),
 * e il risultato torna solo a chi l'ha mandato. Ogni processo agganciato aggiunge
 * la sua parte al risultato:
 *
 *   status            -> stato: versione caricata, monitor, mirroring, telefono
 *   (extra diag=true) -> in piu' la riga [diag] e le ultime righe di log del processo
 *   mirror / desktop  -> monitor in mirroring / di nuovo in desktop (riquadro "Monitor")
 *   touchpad_on/_off  -> telefono come touchpad adesso / telefono normale (riquadro "Touchpad")
 *
 * In system_server il ricevitore si registra a fine avvio (sys.boot_completed);
 * in SystemUI quando parte l'Application.
 */
final class ModuleControl {

    private static final int BOOT_POLL_MS = 10_000;
    private static final int MAX_ATTEMPTS = 60;

    private static Handler handler;
    private static boolean registered;
    private static int attempts;

    // ------------------------------------------------------------------
    // system_server
    // ------------------------------------------------------------------
    static void startSystemServer() {
        handler().postDelayed(REGISTER, 15_000);
    }

    private static final Runnable REGISTER = new Runnable() {
        @Override public void run() {
            if (registered) return;
            attempts++;
            Context ctx = bootCompleted() ? systemContext() : null;
            if (ctx != null) {
                try {
                    IntentFilter f = new IntentFilter(Prefs.ACTION_COMMAND);
                    f.setPriority(100);   // prima di SystemUI nel broadcast ordinato
                    ctx.registerReceiver(SYSTEM_RECEIVER, f, Prefs.PERMISSION_APPLY, handler(),
                            Context.RECEIVER_EXPORTED);
                    registered = true;
                    XLog.i("[control] pronto per i comandi dell'app");
                    return;
                } catch (Throwable t) { XLog.e("[control] registrazione fallita", t); }
            }
            if (attempts < MAX_ATTEMPTS) handler().postDelayed(this, BOOT_POLL_MS);
        }
    };

    private static final BroadcastReceiver SYSTEM_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String cmd = "status";
            try {
                String c0 = i.getStringExtra(Prefs.EXTRA_CMD);
                if (c0 != null) cmd = c0;
                Prefs.refresh();
                switch (cmd) {
                    case "mirror":       setMirror(c, true);  break;
                    case "desktop":      setMirror(c, false); break;
                    case "touchpad_on":  PhoneScreen.touchpadNow(true);  break;
                    case "touchpad_off": PhoneScreen.touchpadNow(false); break;
                    default: break;
                }
                if (isOrderedBroadcast()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("version=").append(BuildConfig.VERSION_NAME).append('\n');
                    sb.append("monitor=").append(ExternalDisplayGate.present ? ExternalDisplayGate.externalId : -1).append('\n');
                    sb.append("mirror=").append(mirrorSetting(c)).append('\n');
                    sb.append("phone=").append(PhoneScreen.state()).append('\n');
                    sb.append("touchpad=").append(Touchpad.active).append('\n');
                    if (i.getBooleanExtra(Prefs.EXTRA_DIAG, false)) {
                        sb.append("\n=== system_server ===\n").append(Diag.last).append("\n\n").append(XLog.recent());
                    }
                    setResultData(concat(getResultData(), sb.toString()));
                }
            } catch (Throwable t) { XLog.e("[control] comando " + cmd + " fallito", t); }
        }
    };

    /**
     * Riquadro "Monitor": scrive la stessa impostazione che usa la finestra di
     * collegamento (Secure.mirror_built_in_display). La guardia del mirroring
     * blocca le scritture di "1" da system_server: questa e' voluta dall'utente,
     * quindi passa (solo questo thread, solo per questa scrittura).
     */
    private static void setMirror(Context c, boolean mirror) {
        int cur = mirrorSetting(c);
        if (mirror && !ExternalDisplayGate.present) { XLog.i("[control] mirroring ignorato: nessun monitor in desktop"); return; }
        if (cur == (mirror ? 1 : 0)) return;   // gia' cosi'
        ContentResolver cr = c.getContentResolver();
        SettingsGuardHooks.USER_WRITE.set(Boolean.TRUE);
        try { Settings.Secure.putInt(cr, SettingsGuardHooks.KEY_MIRROR, mirror ? 1 : 0); }
        finally { SettingsGuardHooks.USER_WRITE.remove(); }
        XLog.i("[control] monitor -> " + (mirror ? "MIRRORING" : "DESKTOP") + " (riquadro)");
        if (mirror) {
            PhoneScreen.onMirroring();             // il monitor ripete il telefono: deve essere acceso
            DockAccessibility.onExternalRemoved(); // e il dock non deve comparire sul telefono
        } else {
            resumeDesktop(c, 0);
        }
    }

    /** Di ritorno al desktop: dock e sessione come dopo la scelta "Computer". */
    private static void resumeDesktop(final Context c, final int tries) {
        handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (!ExternalDisplayGate.present) {
                    if (tries < 16) resumeDesktop(c, tries + 1);
                    else XLog.i("[control] nessun monitor in desktop dopo il cambio");
                    return;
                }
                int id = ExternalDisplayGate.externalId;
                DockAccessibility.onExternalAdded();
                PhoneScreen.onDesktopResumed();
                if (!Prefs.autoLaunch()) return;
                try {
                    Intent di = LauncherHooks.desktopIntent(c, Prefs.launcherPkg());
                    if (di != null) {
                        LauncherHooks.startOnDisplay(c, di, id);
                        XLog.i("[control] dock avviato sul display " + id);
                    }
                    LauncherHooks.launchExtras(c, id, handler());
                } catch (Throwable t) { XLog.e("[control] avvio del dock fallito", t); }
            }
        }, tries == 0 ? 1500 : 500);
    }

    private static int mirrorSetting(Context c) {
        try { return Settings.Secure.getInt(c.getContentResolver(), SettingsGuardHooks.KEY_MIRROR, -1); }
        catch (Throwable t) { return -1; }
    }

    // ------------------------------------------------------------------
    // SystemUI: solo la sua parte del report
    // ------------------------------------------------------------------
    static void hookSystemUi(LoadPackageParam lp) {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (registered) return;
                try {
                    Context ctx = (Context) p.args[0];
                    ctx.registerReceiver(SYSTEMUI_RECEIVER, new IntentFilter(Prefs.ACTION_COMMAND),
                            Prefs.PERMISSION_APPLY, null, Context.RECEIVER_EXPORTED);
                    registered = true;
                } catch (Throwable t) { XLog.e("[control] registrazione in SystemUI fallita", t); }
            }
        });
    }

    private static final BroadcastReceiver SYSTEMUI_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                if (!isOrderedBroadcast() || !i.getBooleanExtra(Prefs.EXTRA_DIAG, false)) return;
                setResultData(concat(getResultData(),
                        "\n=== systemui ===\n" + Diag.last + "\n\n" + XLog.recent()));
            } catch (Throwable t) { XLog.e("[control] report di SystemUI fallito", t); }
        }
    };

    // ------------------------------------------------------------------
    private static String concat(String a, String b) { return a == null ? b : a + b; }

    private static boolean bootCompleted() {
        try {
            Object v = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.SystemProperties", null), "get", "sys.boot_completed");
            return "1".equals(v);
        } catch (Throwable t) { return false; }
    }

    private static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) { return null; }
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-control");
            // in system_server un'eccezione non gestita ucciderebbe il processo
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) {
                    try { XLog.e("[control] eccezione non gestita sul thread", e); } catch (Throwable ignored) {}
                    try {   // il ricevitore usava il Looper morto: lo si toglie e si registra di nuovo
                        Context c = systemContext();
                        if (registered && c != null) c.unregisterReceiver(SYSTEM_RECEIVER);
                    } catch (Throwable ignored) {}
                    registered = false;
                    attempts = 0;
                    synchronized (ModuleControl.class) { handler = null; }
                    try { handler().postDelayed(REGISTER, BOOT_POLL_MS); } catch (Throwable ignored) {}
                }
            });
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    private ModuleControl() {}
}
