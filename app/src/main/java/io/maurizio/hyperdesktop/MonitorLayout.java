/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.graphics.Rect;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostica (1.11.0): com'e' disposto il monitor, per il report dell'app.
 *
 * Elenca le finestre visibili del display esterno, dall'alto in basso (tipo, app,
 * riquadro, modalita' di finestra, inset che la finestra rispetta, flag) e gli inset
 * del display, barra di stato compresa. Serve a capire perche' a volte una finestra
 * finisce sopra la barra di stato del monitor, prima di toccare qualcosa.
 *
 * Solo lettura: sotto il lock del WindowManager, sul thread dei comandi
 * (ModuleControl), mai dentro un hook. Ogni errore diventa una riga del report.
 */
final class MonitorLayout {

    private static final int MAX_WINDOWS = 25;
    private static final int MAX_CHARS   = 6000;

    static String describe(int displayId) {
        if (displayId <= 0) return "nessun monitor in desktop\n";
        StringBuilder sb = new StringBuilder();
        try {
            Object wms = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "window");
            Object lock = XposedHelpers.getObjectField(wms, "mGlobalLock");
            synchronized (lock) {
                Object rootC = XposedHelpers.getObjectField(wms, "mRoot");
                Object dc = XposedHelpers.callMethod(rootC, "getDisplayContent", displayId);
                if (dc == null) return "display " + displayId + " non trovato\n";
                sb.append("display ").append(displayId);
                try { sb.append(" bounds=").append(((Rect) XposedHelpers.callMethod(dc, "getBounds")).toShortString()); }
                catch (Throwable ignored) {}
                sb.append('\n');

                final List<String> lines = new ArrayList<>();
                Consumer<Object> c = new Consumer<Object>() {
                    @Override public void accept(Object ws) {
                        if (lines.size() >= MAX_WINDOWS) return;
                        try {
                            if (!(Boolean) XposedHelpers.callMethod(ws, "isVisible")) return;
                            WindowManager.LayoutParams a =
                                (WindowManager.LayoutParams) XposedHelpers.getObjectField(ws, "mAttrs");
                            Rect f = (Rect) XposedHelpers.callMethod(ws, "getFrame");
                            lines.add("win ty=" + a.type + " " + a.packageName + " '" + title(a) + "'"
                                    + " frame=" + (f == null ? "?" : f.toShortString())
                                    + " mode=" + mode(ws)
                                    + " fit=0x" + Integer.toHexString(a.getFitInsetsTypes())
                                    + " fl=0x" + Integer.toHexString(a.flags)
                                    + " cutout=" + a.layoutInDisplayCutoutMode);
                        } catch (Throwable t) { lines.add("win (non leggibile: " + t.getClass().getSimpleName() + ")"); }
                    }
                };
                XposedHelpers.callMethod(dc, "forAllWindows", c, true);
                for (String l : lines) sb.append(l).append('\n');

                try {
                    Object isc = XposedHelpers.callMethod(dc, "getInsetsStateController");
                    Object st = XposedHelpers.callMethod(isc, "getRawInsetsState");
                    sb.append("insets: ").append(st).append('\n');
                } catch (Throwable t) {
                    sb.append("insets: non leggibili (").append(t.getClass().getSimpleName()).append(")\n");
                }
            }
        } catch (Throwable t) { sb.append("monitor non leggibile: ").append(t).append('\n'); }
        if (sb.length() > MAX_CHARS) { sb.setLength(MAX_CHARS); sb.append("...\n"); }
        return sb.toString();
    }

    private static String title(WindowManager.LayoutParams a) {
        CharSequence t = a.getTitle();
        if (t == null) return "";
        String s = t.toString();
        int slash = s.lastIndexOf('/');   // "pkg/pkg.Activity": basta la parte finale
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String mode(Object ws) {
        try {
            int m = (Integer) XposedHelpers.callMethod(ws, "getWindowingMode");
            switch (m) {
                case 1:  return "fullscreen";
                case 2:  return "pinned";
                case 5:  return "freeform";
                case 6:  return "multi-window";
                default: return String.valueOf(m);
            }
        } catch (Throwable t) { return "?"; }
    }

    private MonitorLayout() {}
}
