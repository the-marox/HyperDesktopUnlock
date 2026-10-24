/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.hardware.input.InputManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.Display;
import android.view.InputDevice;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

/**
 * Legge le dita DIRETTAMENTE dal driver del touchscreen (/dev/input/eventN), come
 * fa InputReader, senza passare dalla catena di input di Android (system_server).
 *
 * Perche': su HyperOS, appena il mouse virtuale si muove mentre un dito e' sul
 * telefono, a monte della consegna alle finestre il tocco viene ANNULLATO (un
 * CANCEL tra gli eventi in ingresso, dopo il primo movimento). Dalla finestra il
 * touchpad vedeva quindi un solo movimento per gesto. Il driver invece continua a
 * mandare tutte le posizioni: piu' lettori dello stesso nodo ricevono ciascuno
 * una copia, e nessuno lo "afferra", quindi Android continua a funzionare.
 *
 * Protocollo multitouch B (slot). Le coordinate grezze si convertono in pixel del
 * telefono con gli assi letti dallo stato di InputReader ("Raw Touch Axes") e
 * ruotate secondo l'orientamento attuale dello schermo (coordinate assolute, come
 * quelle delle finestre). Ogni SYN_REPORT diventa
 * un "fotogramma" (dita appoggiate e posizioni) per Touchpad.onFrame().
 *
 * system_server puo' leggere /dev/input (gruppo input, e' il suo stesso dominio
 * SELinux di InputReader). Thread proprio, con poll a tempo per potersi fermare.
 */
final class RawTouch {

    interface Listener {
        /** n dita appoggiate: id, x, y in pixel del telefono (gia' ruotati). */
        void onFrame(long t, int n, int[] ids, float[] xs, float[] ys);
        /** Stato perso (SYN_DROPPED o errore): il gesto in corso va chiuso. */
        void onReset(long t);
    }

    private static final int EV_SYN = 0, EV_ABS = 3;
    private static final int SYN_REPORT = 0, SYN_DROPPED = 3;
    private static final int ABS_MT_SLOT = 0x2f, ABS_MT_TOOL_TYPE = 0x37;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;
    private static final int MT_TOOL_PALM = 2;
    private static final int SLOTS = 10;
    private static final int EVENT_SIZE = 24;   // struct input_event a 64 bit

    private final String path;
    private final float scaleX, scaleY;
    private final int naturalW, naturalH;   // px del telefono in verticale
    private final Display display;
    private final Listener listener;
    private volatile boolean running = true;
    private Thread thread;

    // stato degli slot (solo thread di lettura)
    private final int[] slotId = new int[SLOTS];
    private final int[] slotX = new int[SLOTS], slotY = new int[SLOTS];
    private int slot;
    private boolean dropping;
    /** Contatti segnalati come "palmo" dal driver (diagnostica: li trattiamo come dita). */
    volatile int palmReports;

    private final int[] fIds = new int[SLOTS];
    private final float[] fX = new float[SLOTS], fY = new float[SLOTS];

    private RawTouch(String path, float scaleX, float scaleY, int naturalW, int naturalH, Display display, Listener l) {
        this.path = path; this.scaleX = scaleX; this.scaleY = scaleY; this.display = display; this.listener = l;
        this.naturalW = naturalW; this.naturalH = naturalH;
        for (int i = 0; i < SLOTS; i++) slotId[i] = -1;
    }

    /** Trova il touchscreen, ne legge la scala e avvia la lettura; null se non possibile. */
    static RawTouch start(Context sys, Display phone, Listener l) {
        try {
            String name = touchscreenName(sys);
            if (name == null) { XLog.i("[touchpad] lettura diretta: touchscreen non trovato"); return null; }
            String dump = nativeDump();
            if (dump == null) return null;
            String node = eventNodeFromDump(dump, name);
            if (node == null) node = eventNode(name);
            if (node == null) { XLog.i("[touchpad] lettura diretta: nodo di " + name + " non trovato"); return null; }
            int[] axes = rawAxes(dump, name);
            if (axes == null) { XLog.i("[touchpad] lettura diretta: assi grezzi di " + name + " non trovati"); return null; }
            Display.Mode mode = phone.getMode();
            int pw = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());   // orientamento naturale (verticale)
            int ph = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
            float sx = pw / (float) (axes[1] - axes[0] + 1);
            float sy = ph / (float) (axes[3] - axes[2] + 1);
            RawTouch r = new RawTouch(node, sx, sy, pw, ph, phone, l);
            r.open();
            XLog.i("[touchpad] lettura diretta da " + node + " (" + name + "), scala " + sx + " x " + sy);
            return r;
        } catch (Throwable t) {
            XLog.e("[touchpad] lettura diretta non disponibile", t);
            return null;
        }
    }

    boolean isRunning() { return running && thread != null && thread.isAlive(); }

    /** Ferma la lettura e aspetta il thread (al massimo mezzo secondo). */
    void stop() {
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
    }

    // ------------------------------------------------------------------
    private void open() throws Exception {
        final FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0);
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(fd); }
        }, "HyperDesktop-rawtouch");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable e) {
                try { XLog.e("[touchpad] errore nella lettura diretta", e); } catch (Throwable ignored) {}
                running = false;
                try { Os.close(fd); } catch (Throwable ignored) {}
            }
        });
        thread.start();
    }

    private void loop(FileDescriptor fd) {
        byte[] buf = new byte[EVENT_SIZE * 64];
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        StructPollfd[] ps = { p };
        try {
            while (running) {
                int r;
                try { r = Os.poll(ps, 250); }
                catch (android.system.ErrnoException e) { if (e.errno == OsConstants.EINTR) continue; throw e; }
                if (r <= 0 || !running) continue;
                int n;
                try { n = Os.read(fd, buf, 0, buf.length); }
                catch (android.system.ErrnoException e) { if (e.errno == OsConstants.EAGAIN) continue; throw e; }
                if (n <= 0) continue;
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                    int type = bb.getShort(off + 16) & 0xffff;
                    int code = bb.getShort(off + 18) & 0xffff;
                    int value = bb.getInt(off + 20);
                    handle(type, code, value);
                }
            }
        } catch (Throwable t) {
            if (running) XLog.e("[touchpad] lettura diretta interrotta", t);
        } finally {
            running = false;
            try { Os.close(fd); } catch (Throwable ignored) {}
            try { listener.onReset(android.os.SystemClock.uptimeMillis()); } catch (Throwable ignored) {}
        }
    }

    private void handle(int type, int code, int value) {
        if (type == EV_SYN) {
            if (code == SYN_DROPPED) { dropping = true; return; }
            if (code != SYN_REPORT) return;
            long t = android.os.SystemClock.uptimeMillis();
            if (dropping) {   // eventi persi: senza rilettura dello stato, chiudiamo il gesto
                dropping = false;
                for (int i = 0; i < SLOTS; i++) slotId[i] = -1;
                listener.onReset(t);
                return;
            }
            emit(t);
            return;
        }
        if (type != EV_ABS || dropping) return;
        switch (code) {
            case ABS_MT_SLOT:        slot = (value >= 0 && value < SLOTS) ? value : -1; break;
            case ABS_MT_TRACKING_ID: if (slot >= 0) slotId[slot] = value; break;
            case ABS_MT_POSITION_X:  if (slot >= 0) slotX[slot] = value; break;
            case ABS_MT_POSITION_Y:  if (slot >= 0) slotY[slot] = value; break;
            case ABS_MT_TOOL_TYPE:   if (value == MT_TOOL_PALM) palmReports++; break;
            default: break;
        }
    }

    private void emit(long t) {
        int rot = Surface.ROTATION_0;
        try { rot = display.getRotation(); } catch (Throwable ignored) {}
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (slotId[i] < 0) continue;
            float x = slotX[i] * scaleX, y = slotY[i] * scaleY;   // pixel, orientamento naturale
            float rx, ry;
            switch (rot) {   // come InputReader: coordinate assolute dello schermo ruotato
                case Surface.ROTATION_90:  rx = y;              ry = naturalW - x; break;
                case Surface.ROTATION_180: rx = naturalW - x;   ry = naturalH - y; break;
                case Surface.ROTATION_270: rx = naturalH - y;   ry = x;            break;
                default:                   rx = x;              ry = y;            break;
            }
            fIds[n] = slotId[i]; fX[n] = rx; fY[n] = ry;
            n++;
        }
        listener.onFrame(t, n, fIds, fX, fY);
    }

    // ------------------------------------------------------------------
    // ricerca del dispositivo
    // ------------------------------------------------------------------
    /** Nome del touchscreen interno, come lo vede Android. */
    private static String touchscreenName(Context sys) {
        InputManager im = sys.getSystemService(InputManager.class);
        if (im == null) return null;
        for (int id : im.getInputDeviceIds()) {
            InputDevice d = im.getInputDevice(id);
            if (d == null || d.isVirtual() || d.isExternal()) continue;
            if ((d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) return d.getName();
        }
        return null;
    }

    /**
     * /dev/input/eventN dalla sezione EventHub dello stato di InputReader:
     *   "    3: Xiaomi_Touch_Input_0\n      Classes: ...\n      Path: /dev/input/event7"
     * (SELinux non lascia leggere /proc/bus/input/devices a system_server su HyperOS).
     */
    private static String eventNodeFromDump(String dump, String name) {
        Matcher m = Pattern.compile("\\n\\s+\\d+: " + Pattern.quote(name)
                + "\\n(?:\\s+[A-Za-z]+:[^\\n]*\\n){0,4}?\\s+Path: (/dev/input/event\\d+)").matcher(dump);
        return m.find() ? m.group(1) : null;
    }

    /** Ripiego: /dev/input/eventN da /proc/bus/input/devices. */
    private static String eventNode(String name) {
        try {
            return eventNodeFromProc(name);
        } catch (Throwable t) {
            XLog.i("[touchpad] /proc/bus/input/devices non leggibile (" + t.getMessage() + ")");
            return null;
        }
    }

    private static String eventNodeFromProc(String name) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            String line;
            boolean match = false;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) {
                    match = line.equals("N: Name=\"" + name + "\"");
                } else if (match && line.startsWith("H: Handlers=")) {
                    for (String h : line.substring(12).trim().split("\\s+")) {
                        if (h.startsWith("event")) return "/dev/input/" + h;
                    }
                }
            }
        }
        return null;
    }

    /**
     * {minX, maxX, minY, maxY} grezzi: dallo stato di InputReader ("Raw Touch Axes"
     * nel blocco del dispositivo), lo stesso testo di "dumpsys input".
     */
    private static int[] rawAxes(String dump, String name) {
        int start = dump.indexOf(": " + name + "\n");
        while (start >= 0) {
            int end = dump.indexOf("\n  Device ", start + 1);
            String block = end > 0 ? dump.substring(start, end) : dump.substring(start);
            Matcher m = Pattern.compile("Raw Touch Axes:\\s*\\n\\s*X: min=(-?\\d+), max=(-?\\d+)[^\\n]*\\n\\s*Y: min=(-?\\d+), max=(-?\\d+)")
                    .matcher(block);
            if (m.find()) {
                return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                   Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)) };
            }
            start = dump.indexOf(": " + name + "\n", start + 1);
        }
        return null;
    }

    private static String nativeDump() {
        Object ims = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "input");
        try {
            Object nat = XposedHelpers.getObjectField(ims, "mNative");
            Object s = XposedHelpers.callMethod(nat, "dump");
            if (s instanceof String) return (String) s;
        } catch (Throwable ignored) {}
        try {   // ripiego: il dump completo del servizio (system_server ha il permesso DUMP)
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            XposedHelpers.callMethod(ims, "dump", new FileDescriptor(), pw, new String[0]);
            pw.flush();
            return sw.toString();
        } catch (Throwable t) {
            XLog.e("[touchpad] stato di InputReader non leggibile", t);
            return null;
        }
    }
}
