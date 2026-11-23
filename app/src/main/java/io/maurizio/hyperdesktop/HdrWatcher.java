/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.HdrConversionMode;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControlHdrLayerInfoListener;

import de.robv.android.xposed.XposedHelpers;

/**
 * Capisce se il monitor esterno sta mostrando HDR (system_server), per sospendere
 * l'oscuramento automatico: il velo nero toglierebbe proprio la luminosita' che
 * l'HDR deve dare.
 *
 * Tre segnali, ognuno facoltativo (se manca si ignora):
 *  1. SurfaceFlinger: livelli HDR sul display (video/foto HDR), via il listener
 *     nascosto SurfaceControlHdrLayerInfoListener. E' quello principale.
 *  2. Uscita forzata in HDR (DisplayManager.getHdrConversionMode, Android 14+).
 *  3. Rapporto HDR/SDR del display > 1 (Display.getHdrSdrRatio, Android 14+).
 *
 * L'HDR attivato solo dal menu del monitor non passa dal telefono: non si vede.
 */
final class HdrWatcher {

    private static Listener listener;
    private static IBinder token;
    private static int watchedDisplay = -1;
    private static volatile int hdrLayers;
    private static String lastSource = "";

    /** Chiamato sul thread di AltTabHooks quando cambia lo stato dei livelli HDR. */
    interface Callback { void onHdrLayersChanged(); }
    private static Callback callback;

    static void start(int displayId, Callback cb) {
        callback = cb;
        if (listener != null && watchedDisplay == displayId) return;
        stop();
        try {
            IBinder t = displayToken(displayId);
            if (t == null) {
                XLog.i("[hdr] token del display " + displayId + " non trovato: solo segnali di riserva");
                return;
            }
            Listener l = new Listener();
            l.register(t);
            listener = l;
            token = t;
            watchedDisplay = displayId;
            XLog.i("[hdr] in ascolto dei livelli HDR sul display " + displayId);
        } catch (Throwable e) {
            XLog.e("[hdr] listener HDR non registrato, solo segnali di riserva", e);
        }
    }

    static void stop() {
        try { if (listener != null && token != null) listener.unregister(token); }
        catch (Throwable e) { XLog.e("[hdr] unregister fallito", e); }
        listener = null;
        token = null;
        watchedDisplay = -1;
        hdrLayers = 0;
    }

    /** True se il monitor sta mostrando HDR secondo almeno uno dei segnali. */
    static boolean active(int displayId) {
        if (hdrLayers > 0) { lastSource = "livelli HDR=" + hdrLayers; return true; }
        Context sys = AltTabHooks.systemContext();
        if (sys == null || Build.VERSION.SDK_INT < 34) { lastSource = ""; return false; }
        try {
            DisplayManager dm = sys.getSystemService(DisplayManager.class);
            if (dm != null) {
                HdrConversionMode m = dm.getHdrConversionMode();
                if (m != null && m.getConversionMode() == HdrConversionMode.HDR_CONVERSION_FORCE
                        && m.getPreferredHdrOutputType() != Display.HdrCapabilities.HDR_TYPE_INVALID) {
                    lastSource = "uscita forzata in HDR (tipo " + m.getPreferredHdrOutputType() + ")";
                    return true;
                }
                Display d = dm.getDisplay(displayId);
                if (d != null && d.isHdrSdrRatioAvailable() && d.getHdrSdrRatio() > 1.05f) {
                    lastSource = "rapporto HDR/SDR " + d.getHdrSdrRatio();
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        lastSource = "";
        return false;
    }

    static String describe() {
        return "hdr: " + (listener != null ? "listener on" : "listener off")
                + ", layers=" + hdrLayers + (lastSource.isEmpty() ? "" : ", " + lastSource);
    }

    // ------------------------------------------------------------------
    /** Token SurfaceFlinger del display fisico dietro il display logico. */
    private static IBinder displayToken(int displayId) {
        Context sys = AltTabHooks.systemContext();
        DisplayManager dm = sys != null ? sys.getSystemService(DisplayManager.class) : null;
        Display d = dm != null ? dm.getDisplay(displayId) : null;
        if (d == null) return null;
        Object addr = XposedHelpers.callMethod(d, "getAddress");   // @hide
        if (addr == null || !addr.getClass().getName().endsWith("DisplayAddress$Physical")) return null;
        long physId = (Long) XposedHelpers.callMethod(addr, "getPhysicalDisplayId");
        // Android 14+: DisplayControl (services.jar); prima: SurfaceControl.
        try {
            Class<?> dc = XposedHelpers.findClass("com.android.server.display.DisplayControl", AltTabHooks.cl);
            Object t = XposedHelpers.callStaticMethod(dc, "getPhysicalDisplayToken", physId);
            if (t instanceof IBinder) return (IBinder) t;
        } catch (Throwable ignored) {}
        try {
            Object t = XposedHelpers.callStaticMethod(
                android.view.SurfaceControl.class, "getPhysicalDisplayToken", physId);
            if (t instanceof IBinder) return (IBinder) t;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void onLayers(final int n) {
        if (n == hdrLayers) return;
        hdrLayers = n;
        XLog.i("[hdr] livelli HDR sul monitor: " + n);
        AltTabHooks.handler().post(new Runnable() {
            @Override public void run() {
                Callback c = callback;
                if (c != null) c.onHdrLayersChanged();
            }
        });
    }

    /** Arriva da un thread binder di SurfaceFlinger. Firma a 5 (<=A14) o 6 (A15+) parametri. */
    private static final class Listener extends SurfaceControlHdrLayerInfoListener {
        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                int maxW, int maxH, int flags) {
            onLayers(numberOfHdrLayers);
        }

        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio) {
            onLayers(numberOfHdrLayers);
        }
    }

    private HdrWatcher() {}
}
