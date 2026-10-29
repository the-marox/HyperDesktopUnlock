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
    /** Tastiera virtuale con il solo Ctrl sinistro: pizzico sul touchpad = Ctrl+rotella (zoom). */
    static final String KEYS_NAME = "HyperDesktop Keys";
    static final String KEYS_PHYS = "hyperdesktop/keys0";

    /**
     * BACK e' il 4o pulsante (BTN_SIDE per Linux): Android lo legge come il tasto
     * "indietro" dei mouse e manda KEYCODE_BACK al display del puntatore, il monitor.
     */
    static final int LEFT = 1, RIGHT = 2, BACK = 8;

    private static final int   UHID_DESTROY = 1;
    private static final int   UHID_CREATE2 = 11;
    private static final int   UHID_INPUT2  = 12;
    private static final short BUS_VIRTUAL  = 0x06;
    private static final int   VENDOR  = 0x1209;   // pid.codes (progetti open source)
    private static final int   PRODUCT = 0x7A11;
    private static final int   KEYS_PRODUCT = 0x7A12;

    private static final byte[] REPORT_DESC = {
        0x05, 0x01,              // Usage Page (Generic Desktop)
        0x09, 0x02,              // Usage (Mouse)
        (byte) 0xA1, 0x01,       // Collection (Application)
        0x09, 0x01,              //   Usage (Pointer)
        (byte) 0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,              //     Usage Page (Buttons)
        0x19, 0x01,              //     Usage Minimum (1)
        0x29, 0x04,              //     Usage Maximum (4)
        0x15, 0x00,              //     Logical Minimum (0)
        0x25, 0x01,              //     Logical Maximum (1)
        (byte) 0x95, 0x04,       //     Report Count (4)
        0x75, 0x01,              //     Report Size (1)
        (byte) 0x81, 0x02,       //     Input (Data, Variable, Absolute): 4 pulsanti (il 4o e' "indietro")
        (byte) 0x95, 0x01,       //     Report Count (1)
        0x75, 0x04,              //     Report Size (4)
        (byte) 0x81, 0x01,       //     Input (Constant): 4 bit di riempimento
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

    /**
     * Tastiera HID con un solo tasto, Ctrl sinistro (report di 1 byte: bit 0).
     * In Android lo stato dei modificatori e' globale (InputReader): mentre Ctrl e'
     * premuto qui, le rotelle del mouse virtuale arrivano alle app come Ctrl+rotella.
     * Senza lettere il sistema non la tratta come una tastiera fisica vera e propria.
     */
    private static final byte[] KEYS_DESC = {
        0x05, 0x01,              // Usage Page (Generic Desktop)
        0x09, 0x06,              // Usage (Keyboard)
        (byte) 0xA1, 0x01,       // Collection (Application)
        0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
        0x19, (byte) 0xE0,       //   Usage Minimum (Left Control)
        0x29, (byte) 0xE0,       //   Usage Maximum (Left Control)
        0x15, 0x00,              //   Logical Minimum (0)
        0x25, 0x01,              //   Logical Maximum (1)
        0x75, 0x01,              //   Report Size (1)
        (byte) 0x95, 0x01,       //   Report Count (1)
        (byte) 0x81, 0x02,       //   Input (Data, Variable, Absolute): Ctrl sinistro
        0x75, 0x07,              //   Report Size (7)
        (byte) 0x95, 0x01,       //   Report Count (1)
        (byte) 0x81, 0x01,       //   Input (Constant): 7 bit di riempimento
        (byte) 0xC0,             // End Collection
    };

    private final FileDescriptor fd;
    private final FileDescriptor keysFd;   // null = niente Ctrl: il pizzico non fa nulla
    private boolean associated;
    private int buttons;
    private boolean ctrlDown;
    private volatile boolean keysReady;   // il sistema ha registrato il Ctrl (vedi check)
    private boolean writeErrorLogged, keysErrorLogged;

    private VirtualMouse(FileDescriptor fd, FileDescriptor keysFd, boolean associated) {
        this.fd = fd;
        this.keysFd = keysFd;
        this.associated = associated;
    }

    /** Crea il mouse e lo lega al display {@code displayId}; null se /dev/uhid non e' utilizzabile. */
    static VirtualMouse open(Context sys, int displayId) {
        boolean assoc = associate(sys, displayId);   // prima della creazione: vale da subito
        FileDescriptor fd = null;
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
            write(fd, create2Event(NAME, PHYS, PRODUCT, REPORT_DESC));
            XLog.i("[touchpad] mouse virtuale creato (uhid" + (assoc ? ", legato al display " + displayId : "") + ")");
            return new VirtualMouse(fd, openKeys(), assoc);
        } catch (Throwable t) {
            XLog.e("[touchpad] /dev/uhid non utilizzabile", t);
            if (fd != null) try { Os.close(fd); } catch (Throwable ignored) {}
            if (assoc) dissociate();
            return null;
        }
    }

    /** Il Ctrl per il pizzico: se non si riesce a crearlo, il mouse funziona lo stesso. */
    private static FileDescriptor openKeys() {
        FileDescriptor k = null;
        try {
            k = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
            write(k, create2Event(KEYS_NAME, KEYS_PHYS, KEYS_PRODUCT, KEYS_DESC));
            return k;
        } catch (Throwable t) {
            XLog.e("[touchpad] tasto Ctrl virtuale non disponibile: niente zoom col pizzico", t);
            if (k != null) try { Os.close(k); } catch (Throwable ignored) {}
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

    /**
     * Il Ctrl virtuale e' pronto (zoom col pizzico)? Solo dopo che il sistema l'ha
     * registrato: senza, Ctrl+rotella arriverebbe come rotella semplice e il pizzico
     * farebbe scorrere la pagina invece dello zoom.
     */
    boolean hasCtrl() { return keysFd != null && keysReady; }

    /** Il Ctrl e' stato creato ma il sistema non l'ha ancora registrato. */
    boolean keysPending() { return keysFd != null && !keysReady; }

    /** Nuovo controllo del solo Ctrl, se al primo check() non era ancora visibile. */
    void checkKeys(Context sys) {
        if (!keysPending()) return;
        try {
            InputManager im = sys.getSystemService(InputManager.class);
            if (im == null) return;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d != null && d.getName() != null && d.getName().startsWith(KEYS_NAME)) {
                    keysReady = true;
                    XLog.i("[touchpad] tasto Ctrl virtuale visto dal sistema: zoom col pizzico attivo");
                    return;
                }
            }
        } catch (Throwable t) { XLog.e("[touchpad] controllo del tasto Ctrl virtuale fallito", t); }
    }

    /** Preme o rilascia Ctrl. false se il tasto non c'e' o l'invio non riesce. */
    boolean ctrl(boolean down) {
        if (keysFd == null) return false;
        if (down == ctrlDown) return true;
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + 1).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_INPUT2).putShort((short) 1).put((byte) (down ? 1 : 0));
        try {
            write(keysFd, b.array());
            ctrlDown = down;
            return true;
        } catch (Throwable t) {
            if (!keysErrorLogged) { keysErrorLogged = true; XLog.e("[touchpad] invio del tasto Ctrl fallito", t); }
            return false;
        }
    }

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
        if (keysFd != null) {
            try { if (ctrlDown) ctrl(false); } catch (Throwable ignored) {}
            try {
                ByteBuffer k = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                k.putInt(UHID_DESTROY);
                write(keysFd, k.array());
            } catch (Throwable ignored) {}
            try { Os.close(keysFd); } catch (Throwable ignored) {}
        }
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
            InputDevice mouseDev = null;
            int mouseId = -1;
            boolean keysSeen = false;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null || d.getName() == null) continue;
                if (d.getName().startsWith(KEYS_NAME)) { keysSeen = true; continue; }
                if (mouseDev == null && d.getName().startsWith(NAME)) { mouseDev = d; mouseId = id; }
            }
            if (keysFd != null) {
                keysReady = keysSeen;
                XLog.i("[touchpad] tasto Ctrl virtuale " + (keysSeen ? "visto dal sistema: zoom col pizzico attivo"
                                                                     : "non (ancora) visto dal sistema"));
            }
            if (mouseDev == null) { XLog.i("[touchpad] il sistema non vede (ancora) il mouse virtuale"); return; }
            boolean enabled = mouseDev.isEnabled();
            XLog.i("[touchpad] il sistema vede il mouse virtuale (dispositivo " + mouseId + ", "
                    + (enabled ? "attivo" : "DISATTIVATO") + ", sorgenti 0x" + Integer.toHexString(mouseDev.getSources()) + ")");
            if (!enabled && associated) {
                dissociate();
                associated = false;
                XLog.i("[touchpad] tolgo il legame al display: il puntatore seguira' il mouse di sistema");
            }
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
    private static byte[] create2Event(String name, String phys, int product, byte[] desc) {
        // struct uhid_event { u32 type; struct uhid_create2_req {
        //   u8 name[128]; u8 phys[64]; u8 uniq[64]; u16 rd_size; u16 bus;
        //   u32 vendor; u32 product; u32 version; u32 country; u8 rd_data[]; } } __packed
        ByteBuffer b = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + desc.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_CREATE2);
        putFixed(b, name, 128);
        putFixed(b, phys, 64);
        putFixed(b, "", 64);
        b.putShort((short) desc.length);
        b.putShort(BUS_VIRTUAL);
        b.putInt(VENDOR);
        b.putInt(product);
        b.putInt(0);   // version
        b.putInt(0);   // country
        b.put(desc);
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
