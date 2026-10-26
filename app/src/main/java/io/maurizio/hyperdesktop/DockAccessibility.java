/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Attiva il servizio di accessibilita' del dock (es. SmartDock DockService)
 * solo mentre il monitor e' in modalita' desktop (system_server).
 *
 * I servizi di accessibilita' attivi partono sempre al boot: senza questa
 * gestione il dock compare anche sul telefono all'accensione. Qui:
 *  - collegamento in desktop  -> servizio attivato, 4 s dopo (a configurazione del
 *                                monitor gia' applicata: vedi ENABLE_DELAY_MS);
 *  - cavo staccato            -> servizio disattivato;
 *  - avvio senza monitor      -> se e' rimasto attivo, viene disattivato.
 * Il servizio viene cercato dinamicamente nel pacchetto del dock scelto nelle
 * impostazioni. Si puo' disattivare (Prefs.manageA11y), letto al momento dell'uso.
 * Il servizio attivato al collegamento viene ricordato e disattivato allo stacco
 * anche se nel frattempo il dock o l'interruttore sono cambiati.
 */
final class DockAccessibility {

    private static final String KEY_SERVICES = "enabled_accessibility_services";
    private static final String KEY_ENABLED  = "accessibility_enabled";
    private static final int[]  BOOT_CHECK_MS = { 30_000, 90_000 };
    /**
     * Attesa prima di attivare il servizio al collegamento: densita', risoluzione e
     * frequenza del monitor (DesktopSession, a 1,5 s) devono essere gia' applicate.
     * Se il servizio riceve un cambio di configurazione mentre si avvia, SmartDock
     * va in crash (DockService.onConfigurationChanged prima di onServiceConnected:
     * "lateinit property context"), si riavvia, e intanto i tasti della tastiera
     * fisica, che passano dal servizio, restano in attesa e arrivano a raffiche.
     */
    private static final int ENABLE_DELAY_MS = 4000;

    private static Handler handler;
    /** Servizio gestito in questa sessione desktop (solo thread handler). */
    private static ComponentName managed;

    static void onExternalAdded() {
        handler().postDelayed(new Runnable() {
            @Override public void run() {
                if (!ExternalDisplayGate.present) return;   // cavo staccato nel frattempo
                ComponentName svc = current();
                if (svc != null && set(svc, true, "collegamento")) managed = svc;
            }
        }, ENABLE_DELAY_MS);
    }

    static void onExternalRemoved() {
        handler().post(new Runnable() {
            @Override public void run() {
                ComponentName svc = managed != null ? managed : current();
                managed = null;
                if (svc != null) set(svc, false, "cavo staccato");
            }
        });
    }

    /** Dopo il boot: se non c'e' un monitor in desktop, il dock non deve restare attivo. */
    static void scheduleBootCheck() {
        for (int ms : BOOT_CHECK_MS) {
            handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (ExternalDisplayGate.present) return;
                    ComponentName svc = current();
                    if (svc != null) set(svc, false, "avvio senza monitor");
                }
            }, ms);
        }
    }

    /** Servizio del dock attuale, o null se la gestione e' spenta / il dock non ne ha. */
    private static ComponentName current() {
        try {
            if (!Prefs.manageA11y()) return null;
            Context ctx = systemContext();
            if (ctx == null) return null;
            String pkg = Prefs.launcherPkg();
            ComponentName svc = findService(ctx, pkg);
            if (svc == null) XLog.i("[dock] nessun servizio di accessibilita' in " + pkg);
            return svc;
        } catch (Throwable t) {
            XLog.e("[dock] ricerca del servizio fallita", t);
            return null;
        }
    }

    /** @return true se, alla fine, il servizio e' nello stato voluto. */
    private static boolean set(ComponentName svc, boolean enable, String why) {
        try {
            Context ctx = systemContext();
            if (ctx == null) return false;

            ContentResolver cr = ctx.getContentResolver();
            String cur = Settings.Secure.getString(cr, KEY_SERVICES);
            List<String> items = new ArrayList<>();
            boolean present = false;
            if (!TextUtils.isEmpty(cur)) {
                for (String s : cur.split(":")) {
                    if (s.isEmpty()) continue;
                    ComponentName cn = ComponentName.unflattenFromString(s);
                    if (svc.equals(cn)) { present = true; if (!enable) continue; }
                    items.add(s);
                }
            }
            if (enable && !present) items.add(svc.flattenToString());
            if (enable == present) return true;   // gia' nello stato voluto

            Settings.Secure.putString(cr, KEY_SERVICES, TextUtils.join(":", items));
            if (enable) Settings.Secure.putInt(cr, KEY_ENABLED, 1);
            XLog.i("[dock] servizio " + svc.flattenToShortString() + (enable ? " ATTIVATO" : " DISATTIVATO") + " (" + why + ")");
            return true;
        } catch (Throwable t) {
            XLog.e("[dock] gestione accessibilita' fallita", t);
            return false;
        }
    }

    private static ComponentName findService(Context ctx, String pkg) {
        Intent q = new Intent("android.accessibilityservice.AccessibilityService")
                .setPackage(pkg);
        List<ResolveInfo> r = ctx.getPackageManager().queryIntentServices(q, 0);
        if (r == null || r.isEmpty()) return null;
        return new ComponentName(r.get(0).serviceInfo.packageName, r.get(0).serviceInfo.name);
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-dock");
            // in system_server un'eccezione non gestita ucciderebbe il processo: la fermiamo
            // qui, e il thread viene ricreato al prossimo uso
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) {
                    try { XLog.e("[dock] eccezione non gestita sul thread, lo ricreo", e); } catch (Throwable ignored) {}
                    synchronized (DockAccessibility.class) { handler = null; }
                }
            });
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    private static Context systemContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) { return null; }
    }

    private DockAccessibility() {}
}
