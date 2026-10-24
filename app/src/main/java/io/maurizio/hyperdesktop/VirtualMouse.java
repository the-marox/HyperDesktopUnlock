/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.system.Os;
import android.system.OsConstants;
import android.view.Display;
import android.view.InputDevice;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedHelpers;

/**
 * Mouse virtuale vero (system_server), creato con /dev/uhid come fa scrcpy con
 * --mouse=uhid.
 *
 * Gli eventi iniettati (InputManager.injectInputEvent) arrivano alle app ma NON
 * muovono il puntatore disegnato dal sistema. Un dispositivo HID invece passa da
 * InputReader e PointerChoreographer come un mouse fisico: il cursore sul monitor
 * si muove, con l'accelerazione e la velocita' del puntatore di sistema.
 *
 * Protocollo uhid (linux/uhid.h): si scrivono strutture uhid_event, niente ioctl.
 *   UHID_CREATE2 crea il dispositivo con il descrittore HID qui sotto,
 *   UHID_INPUT2 manda un report, UHID_DESTROY (o la chiusura del fd) lo toglie.
 * Ogni write() e' un evento intero. Report di 5 byte:
 *   [pulsanti, dx, dy, rotella, rotella orizzontale].
 *
 * Il dispositivo viene associato al display del monitor (porta di input ->
 * uniqueId del display), cosi' il puntatore sta sul monitor anche senza un mouse
 * fisico. Se l'associazione non esiste su questa build, o disattiva il dispositivo,
 * la togliamo e il puntatore segue il mouse di sistema.
 *
 * Tutti i metodi vanno chiamati dallo stesso thread (quello di PhoneScreen).
 */
final class VirtualMouse {

    static final String NAME = "HyperDesktop Touchpad";
    static final String PHYS = "hyperdesktop/touchpad0";

    static final int LEFT = 1, RIGHT = 2;

    private static final int   UHID_DESTROY = 1;
    private static final int   UHID_CREATE2 = 11;
    private static final int   UHID_INPUT2  = 12;
    private static final short BUS_VIRTUAL  = 0x06;
    private static final int   VENDOR  = 0x1209;   // pid.codes (progetti open source)
    private static final int   PRODUCT = 0x7A11;

    private static final byte[] REPORT_DESC = {
        0x05, 0x01,              // Usage Page (Generic Desktop)
        0x09, 0x02,              // Usage (Mouse)
        (byte) 0xA1, 0x01,       // Collection (Application)
        0x09, 0x01,              //   Usage (Pointer)
        (byte) 0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,              //     Usage Page (Buttons)
        0x19, 0x01,              //     Usage Minimum (1)
        0x29, 0x03,              //     Usage Maximum (3)
        0x15, 0x00,              //     Logical Minimum (0)
        0x25, 0x01,              //     Logical Maximum (1)
        (byte) 0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,              //     Report Size (1)
        (byte) 0x81, 0x02,       //     Input (Data, Variable, Absolute): 3 pulsanti
        (byte) 0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,              //     Report Size (5)
        (byte) 0x81, 0x01,       //     Input (Constant): 5 bit di riempimento
        0x05, 0x01,              //     Usage Page (Generic Desktop)
        0x09, 0x30,              //     Usage (X)
        0x09, 0x31,              //     Usage (Y)
        0x09, 0x38,              //     Usage (Wheel)
        0x15, (byte) 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,              //     Logical Maximum (127)
        0x75, 0x08,              //     Report Size (8)
        (byte) 0x95, 0x03,       //     Report Count (3)
        (byte) 0x81, 0x06,       //     Input (Data, Variable, Relative): X, Y, rotella
        0x05, 0x0C,              //     Usage Page (Consumer)
        0x0A, 0x38, 0x02,        //     Usage (AC Pan)
        0x15, (byte) 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,              //     Logical Maximum (127)
        0x75, 0x08,              //     Report Size (8)
        (byte) 0x95, 0x01,       //     Report Count (1)
        (byte) 0x81, 0x06,       //     Input (Data, Variable, Relative): rotella orizzontale
        (byte) 0xC0,             //   End Collection
        (byte) 0xC0,             // End Collection
    };

    private final FileDescriptor fd;
    private boolean associated;
    private int buttons;
    private boolean writeErrorLogged;

    private VirtualMouse(FileDescriptor fd, boolean associated) {
        this.fd = fd;
        this.associated = associated;
    }

    /** Crea il mouse e lo lega al display {@code displayId}; null se /dev/uhid non e' utilizzabile. */
    static VirtualMouse open(Context sys, int displayId) {
        boolean assoc = associate(sys, displayId);   // prima della creazione: vale da subito
        FileDescriptor fd = null;
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
            write(fd, create2Event());
            XLog.i("[touchpad] mouse virtuale creato (uhid" + (assoc ? ", legato al display " + displayId : "") + ")");
            return new VirtualMouse(fd, assoc);
        } catch (Throwable t) {
            XLog.e("[touchpad] /dev/uhid non utilizzabile", t);
            if (fd != null) try { Os.close(fd); } catch (Throwable ignored) {}
            if (assoc) dissociate();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // eventi
    // ------------------------------------------------------------------
    void move(int dx, int dy) {
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx), sy = clamp(dy);
            send(sx, sy, 0, 0);
            dx -= sx; dy -= sy;
        }
    }

    /** Scatti di rotella: {@code v} > 0 = su, {@code h} > 0 = destra. */
    void scroll(int v, int h) {
        while (v != 0 || h != 0) {
            int sv = clamp(v), sh = clamp(h);
            send(0, 0, sv, sh);
            v -= sv; h -= sh;
        }
    }

    void press(int button)   { buttons |= button;  send(0, 0, 0, 0); }
    void release(int button) { buttons &= ~button; send(0, 0, 0, 0); }
    void click(int button)   { press(button); release(button); }

    private void send(int dx, int dy, int wheel, int pan) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 5).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_INPUT2).putShort((short) 5)
         .put((byte) buttons).put((byte) dx).put((byte) dy).put((byte) wheel).put((byte) pan);
        try {
            write(fd, b.array());
        } catch (Throwable t) {
            if (!writeErrorLogged) { writeErrorLogged = true; XLog.e("[touchpad] invio al mouse virtuale fallito", t); }
        }
    }

    void close() {
        try { if (buttons != 0) { buttons = 0; send(0, 0, 0, 0); } } catch (Throwable ignored) {}
        try {
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(UHID_DESTROY);
            write(fd, b.array());
        } catch (Throwable ignored) {}
        try { Os.close(fd); } catch (Throwable ignored) {}
        if (associated) { dissociate(); associated = false; }
        XLog.i("[touchpad] mouse virtuale rimosso");
    }

    /**
     * Diagnostica, un attimo dopo la creazione: il sistema vede il dispositivo?
     * Se l'associazione al display lo ha disattivato, la togliamo (si riattiva).
     */
    void check(Context sys) {
        try {
            InputManager im = sys.getSystemService(InputManager.class);
            if (im == null) return;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null || d.getName() == null || !d.getName().startsWith(NAME)) continue;
                boolean enabled = d.isEnabled();
                XLog.i("[touchpad] il sistema vede il mouse virtuale (dispositivo " + id + ", "
                        + (enabled ? "attivo" : "DISATTIVATO") + ", sorgenti 0x" + Integer.toHexString(d.getSources()) + ")");
                if (!enabled && associated) {
                    dissociate();
                    associated = false;
                    XLog.i("[touchpad] tolgo il legame al display: il puntatore seguira' il mouse di sistema");
                }
                return;
            }
            XLog.i("[touchpad] il sistema non vede (ancora) il mouse virtuale");
        } catch (Throwable t) { XLog.e("[touchpad] controllo del mouse virtuale fallito", t); }
    }

    // ------------------------------------------------------------------
    // associazione dispositivo -> display del monitor
    // ------------------------------------------------------------------
    private static boolean associate(Context sys, int displayId) {
        try {
            DisplayManager dm = sys.getSystemService(DisplayManager.class);
            Display d = dm == null ? null : dm.getDisplay(displayId);
            if (d == null) return false;
            String uniqueId = (String) XposedHelpers.callMethod(d, "getUniqueId");   // @hide
            if (uniqueId == null || uniqueId.isEmpty()) return false;
            Object ims = inputService();
            try { XposedHelpers.callMethod(ims, "addUniqueIdAssociationByPort", PHYS, uniqueId); }   // Android 15+
            catch (NoSuchMethodError e) { XposedHelpers.callMethod(ims, "addUniqueIdAssociation", PHYS, uniqueId); }
            return true;
        } catch (Throwable t) {
            XLog.i("[touchpad] legame al display non disponibile (" + t + "): il puntatore segue il mouse di sistema");
            return false;
        }
    }

    private static void dissociate() {
        try {
            Object ims = inputService();
            try { XposedHelpers.callMethod(ims, "removeUniqueIdAssociationByPort", PHYS); }
            catch (NoSuchMethodError e) { XposedHelpers.callMethod(ims, "removeUniqueIdAssociation", PHYS); }
        } catch (Throwable t) { XLog.e("[touchpad] rimozione del legame al display fallita", t); }
    }

    /** In system_server ServiceManager restituisce direttamente l'oggetto InputManagerService. */
    private static Object inputService() {
        return XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "input");
    }

    // ------------------------------------------------------------------
    // strutture uhid
    // ------------------------------------------------------------------
    private static byte[] create2Event() {
        // struct uhid_event { u32 type; struct uhid_create2_req {
        //   u8 name[128]; u8 phys[64]; u8 uniq[64]; u16 rd_size; u16 bus;
        //   u32 vendor; u32 product; u32 version; u32 country; u8 rd_data[]; } } __packed
        ByteBuffer b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + REPORT_DESC.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_CREATE2);
        putFixed(b, NAME, 128);
        putFixed(b, PHYS, 64);
        putFixed(b, "", 64);
        b.putShort((short) REPORT_DESC.length);
        b.putShort(BUS_VIRTUAL);
        b.putInt(VENDOR);
        b.putInt(PRODUCT);
        b.putInt(0);   // version
        b.putInt(0);   // country
        b.put(REPORT_DESC);
        return b.array();
    }

    private static void putFixed(ByteBuffer b, String s, int len) {
        byte[] u = s.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(u.length, len - 1);
        b.put(u, 0, n);
        for (int i = n; i < len; i++) b.put((byte) 0);
    }

    /** Il kernel tratta ogni write() come un evento intero: niente scritture parziali. */
    private static void write(FileDescriptor fd, byte[] data) throws Exception {
        int n = Os.write(fd, data, 0, data.length);
        if (n != data.length) throw new IOException("uhid write " + n + "/" + data.length);
    }

    private static int clamp(int v) {
        return v > 127 ? 127 : (v < -127 ? -127 : v);
    }
}
