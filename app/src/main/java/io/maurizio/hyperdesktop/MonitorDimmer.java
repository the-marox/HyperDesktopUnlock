/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/**
 * Oscuramento automatico del monitor esterno (system_server), guidato dal sensore
 * di luce del telefono.
 *
 * Android non puo' regolare la retroilluminazione di un monitor DisplayPort (niente
 * DDC/CI senza i2c-dev nel kernel, quasi nessun monitor espone il controllo via USB),
 * quindi si oscura via software: una finestra nera a tutto schermo, non toccabile e
 * non a fuoco, sopra tutto sul display esterno, con trasparenza che segue la luce.
 * Luce forte = finestra trasparente; buio = oscuramento massimo scelto nell'app.
 *
 * Tutto gira sul thread di AltTabHooks (Looper), come SwitcherOverlay. Qualsiasi
 * errore spegne l'oscuramento: il monitor resta com'era, mai nero.
 */
final class MonitorDimmer {

    /** TYPE_SECURE_SYSTEM_OVERLAY (@hide): sopra barre e overlay delle app. */
    private static final int TYPE_SECURE_OVERLAY = 2015;

    /** Sopra questa luce niente oscuramento, sotto la minima oscuramento massimo (lux). */
    private static final float LUX_BRIGHT = 200f;   // stanza ben illuminata: immagine piena
    private static final float LUX_DARK   = 3f;
    /** Media mobile sul log dei lux: niente sbalzi per un'ombra di passaggio. */
    private static final float SMOOTH = 0.15f;
    /** Variazione minima che vale la pena applicare (frazione 0..1). */
    private static final float STEP = 0.02f;
    private static final long FADE_MS = 900;
    private static final long LOG_EVERY_MS = 60_000;

    private static WindowManager wm;
    private static View veil;
    private static int veilDisplay = -1;
    private static SensorManager sm;
    private static SensorEventListener listener;

    private static float logLux = Float.NaN;
    private static float lastLux = -1;
    private static float applied = 0f;
    private static long lastLog;

    // ------------------------------------------------------------------
    // ciclo di vita (chiamati da DesktopSession, sempre sul thread di AltTabHooks)
    // ------------------------------------------------------------------
    /** Monitor collegato o impostazioni cambiate: accende, aggiorna o spegne. */
    static void sync() {
        try {
            int id = ExternalDisplayGate.externalId;
            if (!Prefs.autoDim() || !ExternalDisplayGate.present || id <= 0 || !ModuleState.enabled()) {
                stop();
                return;
            }
            if (veil != null && veilDisplay != id) stop();   // monitor cambiato
            if (veil == null && !addVeil(id)) return;
            if (listener == null) startSensor();
            update(true);                                      // oscuramento massimo cambiato
        } catch (Throwable t) {
            XLog.e("[dim] avvio fallito, oscuramento spento", t);
            stop();
        }
    }

    static void stop() {
        stopSensor();
        try {
            if (veil != null && wm != null) wm.removeViewImmediate(veil);
        } catch (Throwable t) { XLog.e("[dim] removeView fallito", t); }
        if (veil != null) XLog.i("[dim] oscuramento spento");
        veil = null;
        wm = null;
        veilDisplay = -1;
        applied = 0f;
        logLux = Float.NaN;
    }

    /** Il thread di AltTabHooks e' morto: la finestra era sua, si riparte da zero. */
    static void onHandlerLost() {
        stop();
        AltTabHooks.handler().post(new Runnable() {
            @Override public void run() { sync(); }
        });
    }

    /** Per il report diagnostico. */
    static String describe() {
        if (veil == null) return "auto-dim: " + (Prefs.autoDim() ? "on, idle" : "off");
        return "auto-dim: active, lux=" + Math.round(lastLux) + " dim=" + Math.round(applied * 100) + "%";
    }

    // ------------------------------------------------------------------
    private static boolean addVeil(int displayId) {
        Context sys = AltTabHooks.systemContext();
        if (sys == null) return false;
        DisplayManager dm = sys.getSystemService(DisplayManager.class);
        Display disp = dm != null ? dm.getDisplay(displayId) : null;
        if (disp == null) return false;
        Context dctx = sys.createDisplayContext(disp);

        int[] types = { TYPE_SECURE_OVERLAY, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY };
        for (int type : types) {
            WindowManager w = null;
            View v = null;
            try {
                Context wctx = dctx.createWindowContext(disp, type, null);
                w = wctx.getSystemService(WindowManager.class);
                if (w == null) continue;
                v = new View(wctx);
                v.setBackgroundColor(Color.BLACK);
                v.setAlpha(0f);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setFitInsetsTypes(0);
                lp.setTitle("HyperDesktop Dimmer");
                w.addView(v, lp);
                wm = w;
                veil = v;
                veilDisplay = displayId;
                XLog.i("[dim] velo aggiunto sul display " + displayId + " (tipo " + type + ")");
                return true;
            } catch (Throwable t) {
                XLog.e("[dim] velo di tipo " + type + " non aggiunto", t);
                try { if (w != null && v != null && v.isAttachedToWindow()) w.removeViewImmediate(v); }
                catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void startSensor() {
        Context sys = AltTabHooks.systemContext();
        sm = sys != null ? sys.getSystemService(SensorManager.class) : null;
        Sensor s = sm != null ? sm.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        if (s == null) {
            XLog.i("[dim] nessun sensore di luce: oscuramento non attivo");
            return;
        }
        listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (e.values == null || e.values.length == 0) return;
                onLux(e.values[0]);
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        boolean ok = sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL, AltTabHooks.handler());
        XLog.i("[dim] sensore " + s.getName() + (ok ? " registrato" : " NON registrato"));
        if (!ok) listener = null;
    }

    private static void stopSensor() {
        try { if (sm != null && listener != null) sm.unregisterListener(listener); }
        catch (Throwable ignored) {}
        listener = null;
        sm = null;
    }

    private static void onLux(float lux) {
        if (veil == null) return;
        lastLux = Math.max(0f, lux);
        float l = (float) Math.log10(lastLux + 1f);
        logLux = Float.isNaN(logLux) ? l : logLux + SMOOTH * (l - logLux);
        update(false);
    }

    /**
     * Oscuramento 0..1 dalla luce media, tra LUX_DARK e LUX_BRIGHT sul logaritmo dei lux.
     * Curva k^1.5: con luce da interni (50-150 lux) si scurisce poco, il grosso
     * dell'oscuramento arriva solo quando la stanza e' davvero buia.
     */
    private static float target() {
        float max = Prefs.dimMax() / 100f;
        if (Float.isNaN(logLux)) return 0f;   // ancora nessuna lettura
        float lo = (float) Math.log10(LUX_DARK + 1f);
        float hi = (float) Math.log10(LUX_BRIGHT + 1f);
        float k = (hi - logLux) / (hi - lo);   // 0 = luce forte, 1 = buio
        k = Math.max(0f, Math.min(1f, k));
        return (float) Math.pow(k, 1.5) * max;
    }

    private static void update(boolean force) {
        if (veil == null) return;
        float t = target();
        if (!force && Math.abs(t - applied) < STEP && !(t == 0f && applied > 0f)) return;
        applied = t;
        veil.animate().cancel();
        veil.animate().alpha(t).setDuration(FADE_MS).start();
        long now = SystemClock.uptimeMillis();
        if (force || now - lastLog >= LOG_EVERY_MS) {
            lastLog = now;
            XLog.i("[dim] lux " + Math.round(lastLux) + " -> oscuramento " + Math.round(t * 100) + "%");
        }
    }

    private MonitorDimmer() {}
}
