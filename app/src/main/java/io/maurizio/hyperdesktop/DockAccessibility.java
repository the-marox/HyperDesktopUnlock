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
 *  - collegamento in desktop  -> servizio attivato (prima che parta il launcher);
 *  - cavo staccato            -> servizio disattivato;
 *  - avvio senza monitor      -> se e' rimasto attivo, viene disattivato.
 * Il servizio viene cercato dinamicamente nel pacchetto LauncherHooks.LAUNCHER_PACKAGE.
 */
final class DockAccessibility {

    static final boolean MANAGE = true;

    private static final String KEY_SERVICES = "enabled_accessibility_services";
    private static final String KEY_ENABLED  = "accessibility_enabled";
    private static final int[]  BOOT_CHECK_MS = { 30_000, 90_000 };

    private static Handler handler;

    static void onExternalAdded() {
        if (!MANAGE) return;
        handler().post(new Runnable() { @Override public void run() { set(true, "collegamento"); } });
    }

    static void onExternalRemoved() {
        if (!MANAGE) return;
        handler().post(new Runnable() { @Override public void run() { set(false, "cavo staccato"); } });
    }

    /** Dopo il boot: se non c'e' un monitor in desktop, il dock non deve restare attivo. */
    static void scheduleBootCheck() {
        if (!MANAGE) return;
        for (int ms : BOOT_CHECK_MS) {
            handler().postDelayed(new Runnable() {
                @Override public void run() {
                    if (!ExternalDisplayGate.present) set(false, "avvio senza monitor");
                }
            }, ms);
        }
    }

    private static void set(boolean enable, String why) {
        try {
            Context ctx = systemContext();
            if (ctx == null) return;
            ComponentName svc = findService(ctx);
            if (svc == null) { XLog.i("[dock] nessun servizio di accessibilita' in " + LauncherHooks.LAUNCHER_PACKAGE); return; }

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
            if (enable == present) return;   // gia' nello stato voluto

            Settings.Secure.putString(cr, KEY_SERVICES, TextUtils.join(":", items));
            if (enable) Settings.Secure.putInt(cr, KEY_ENABLED, 1);
            XLog.i("[dock] servizio " + svc.flattenToShortString() + (enable ? " ATTIVATO" : " DISATTIVATO") + " (" + why + ")");
        } catch (Throwable t) {
            XLog.e("[dock] gestione accessibilita' fallita", t);
        }
    }

    private static ComponentName findService(Context ctx) {
        Intent q = new Intent("android.accessibilityservice.AccessibilityService")
                .setPackage(LauncherHooks.LAUNCHER_PACKAGE);
        List<ResolveInfo> r = ctx.getPackageManager().queryIntentServices(q, 0);
        if (r == null || r.isEmpty()) return null;
        return new ComponentName(r.get(0).serviceInfo.packageName, r.get(0).serviceInfo.name);
    }

    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-dock");
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
