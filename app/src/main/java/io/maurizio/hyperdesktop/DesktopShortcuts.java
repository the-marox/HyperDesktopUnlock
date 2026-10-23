/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Scorciatoie da tastiera per la modalita' desktop (system_server).
 * Chiamate da AltTabHooks.onKey, quindi gia' all'ingresso dell'input
 * (InputManagerService.interceptKeyBeforeQueueing). Attive solo mentre un
 * display esterno e' in modalita' desktop (ExternalDisplayGate.present).
 *
 *   Stamp / Win+Shift+S   screenshot del display col focus, se e' il monitor
 *                         (sul telefono resta lo screenshot di HyperOS)
 *   Win+Shift+frecce      sposta l'app in primo piano monitor <-> telefono
 *   Alt+F4                chiude l'app in primo piano sul monitor
 *   Win+D                 mostra il desktop del dock sul monitor
 *   Win+P                 accende/spegne lo schermo del telefono
 *
 * Si disattivano tutte (con Alt+Tab) dalle impostazioni (Prefs.shortcuts).
 * Quando una combinazione con Win viene consumata, consumiamo anche il
 * rilascio di Win: altrimenti il sistema (e SmartDock) vedrebbero "Win premuto
 * da solo" e aprirebbero il menu delle app.
 */
final class DesktopShortcuts {

    private enum Action {
        SCREENSHOT(true), MOVE(false), CLOSE(true), SHOW_DESKTOP(true), TOGGLE_PHONE(false),
        BACK(true), FOCUS_APP(true);

        /** true = solo con il focus sul monitor; sul telefono il tasto fa quello di sempre. */
        final boolean monitorOnly;
        Action(boolean monitorOnly) { this.monitorOnly = monitorOnly; }
    }

    private static final Set<Integer> consumedDowns = Collections.synchronizedSet(new HashSet<Integer>());
    private static volatile boolean metaComboUsed = false;

    /** @return true se l'evento va consumato. Thread di input: deve essere veloce. */
    static boolean onKey(KeyEvent ev) {
        final int code = ev.getKeyCode();
        final int action = ev.getAction();

        if (code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT) {
            if (action == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) metaComboUsed = false;
            if (action == KeyEvent.ACTION_UP && metaComboUsed) { metaComboUsed = false; return true; }
            return false;
        }
        if (action == KeyEvent.ACTION_UP) return consumedDowns.remove(code);
        if (action != KeyEvent.ACTION_DOWN) return false;
        if (ev.getRepeatCount() > 0) return consumedDowns.contains(code);
        if (!ExternalDisplayGate.present) return false;

        final Action a = match(ev);
        if (a == null || !Prefs.shortcuts()) return false;

        final int focused = AltTabHooks.topFocusedDisplayId();
        if (a.monitorOnly && focused <= 0) return false;  // telefono: comportamento normale

        consumedDowns.add(code);
        if (ev.isMetaPressed()) metaComboUsed = true;
        AltTabHooks.handler().post(new Runnable() {
            @Override public void run() {
                try { perform(a, focused, code); } catch (Throwable t) { XLog.e("[keys] azione " + a + " fallita", t); }
            }
        });
        return true;
    }

    private static Action match(KeyEvent ev) {
        int c = ev.getKeyCode();
        boolean meta = ev.isMetaPressed(), shift = ev.isShiftPressed(),
                alt = ev.isAltPressed(), ctrl = ev.isCtrlPressed();
        if (c == KeyEvent.KEYCODE_SYSRQ) return Action.SCREENSHOT;
        if (meta && shift && !alt && !ctrl && c == KeyEvent.KEYCODE_S) return Action.SCREENSHOT;
        if (meta && shift && !alt && !ctrl && (c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT
                || c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN)) return Action.MOVE;
        if (alt && !meta && !ctrl && c == KeyEvent.KEYCODE_F4) return Action.CLOSE;
        if (meta && !shift && !alt && !ctrl && c == KeyEvent.KEYCODE_D) return Action.SHOW_DESKTOP;
        if (meta && !shift && !alt && !ctrl && c == KeyEvent.KEYCODE_P) return Action.TOGGLE_PHONE;
        if (meta && !shift && !alt && !ctrl && c == KeyEvent.KEYCODE_DEL) return Action.BACK;  // Win+Backspace
        if (meta && !shift && !alt && !ctrl && c >= KeyEvent.KEYCODE_1 && c <= KeyEvent.KEYCODE_9) return Action.FOCUS_APP;
        return null;
    }

    private static void perform(Action a, int focused, int keyCode) throws Throwable {
        switch (a) {
            case SCREENSHOT:   screenshot(focused); break;
            case CLOSE:        closeTop(focused); break;
            case SHOW_DESKTOP: showDesktop(focused); break;
            case TOGGLE_PHONE: PhoneScreen.toggle(); break;
            case BACK:         injectBack(focused); break;
            case FOCUS_APP:    focusNth(focused, keyCode - KeyEvent.KEYCODE_1); break;
            case MOVE: {
                int ext = ExternalDisplayGate.externalId;
                if (ext <= 0) return;
                int from = focused > 0 ? focused : 0;
                int to   = focused > 0 ? 0 : ext;
                moveTop(from, to);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // azioni
    // ------------------------------------------------------------------
    private static Object atm() {
        return XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityTaskManager", null), "getService");
    }

    private static void closeTop(int displayId) {
        List<Integer> t = AltTabHooks.tasksOn(displayId);
        if (t.isEmpty()) { XLog.i("[keys] Alt+F4: nessuna app sul display " + displayId); return; }
        XposedHelpers.callMethod(atm(), "removeTask", t.get(0));
        XLog.i("[keys] Alt+F4: chiuso task " + t.get(0) + " sul display " + displayId);
    }

    /** Win+D: porta in primo piano il desktop del dock; se non c'e', lo avvia. */
    private static void showDesktop(int displayId) {
        Context ctx = AltTabHooks.systemContext();
        if (ctx == null) return;
        String pkg = Prefs.launcherPkg();
        Intent desktop = LauncherHooks.desktopIntent(ctx, pkg);
        if (desktop == null) { XLog.i("[keys] Win+D: " + pkg + " non installato"); return; }
        int task = AltTabHooks.taskOf(displayId, desktop.getComponent());
        if (task >= 0) {
            AltTabHooks.moveToFront(task);
            XLog.i("[keys] Win+D: desktop del dock in primo piano (task " + task + ")");
        } else {
            LauncherHooks.startOnDisplay(ctx, desktop, displayId);
            XLog.i("[keys] Win+D: avvio del desktop del dock richiesto sul display " + displayId);
        }
    }

    /** Win+Backspace: invia Indietro alla finestra a fuoco sul monitor. */
    private static void injectBack(int displayId) {
        Context ctx = AltTabHooks.systemContext();
        if (ctx == null) return;
        InputManager im = ctx.getSystemService(InputManager.class);
        if (im == null) return;
        long now = SystemClock.uptimeMillis();
        inject(im, backEvent(now, KeyEvent.ACTION_DOWN), displayId);
        inject(im, backEvent(now, KeyEvent.ACTION_UP), displayId);
        XLog.i("[keys] Win+Backspace: Indietro inviato al display " + displayId);
    }

    private static KeyEvent backEvent(long when, int action) {
        return new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    private static void inject(InputManager im, KeyEvent ev, int displayId) {
        try { XposedHelpers.callMethod(ev, "setDisplayId", displayId); }
        catch (Throwable t) { XLog.e("[keys] setDisplayId non disponibile, Indietro potrebbe finire sul telefono", t); }
        try { XposedHelpers.callMethod(im, "injectInputEvent", ev, 0 /* ASYNC */); }
        catch (Throwable t) { XLog.e("[keys] injectInputEvent fallito", t); }
    }

    /**
     * Win+1..9: porta in primo piano l'ennesima app aperta sul monitor.
     * Ordiniamo per taskId (~ ordine di apertura) cosi' la posizione e' STABILE:
     * Win+1 e' sempre la stessa app anche quando cambia quella a fuoco.
     */
    private static void focusNth(int displayId, int index) {
        if (index < 0) return;
        List<Integer> tasks = AltTabHooks.tasksOn(displayId);
        java.util.Collections.sort(tasks);
        if (index >= tasks.size()) { XLog.i("[keys] Win+" + (index + 1) + ": nessuna app in posizione " + (index + 1)); return; }
        AltTabHooks.moveToFront(tasks.get(index));
        XLog.i("[keys] Win+" + (index + 1) + ": task " + tasks.get(index) + " in primo piano sul display " + displayId);
    }

    private static void moveTop(int from, int to) {
        List<Integer> t = AltTabHooks.tasksOn(from);
        if (t.isEmpty()) { XLog.i("[keys] sposta: nessuna app sul display " + from); return; }
        int task = t.get(0);
        Object atm = atm();
        try {
            XposedHelpers.callMethod(atm, "moveRootTaskToDisplay", task, to);
            XLog.i("[keys] task " + task + " spostato dal display " + from + " al " + to);
        } catch (Throwable e) {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(to);
            XposedHelpers.callMethod(atm, "startActivityFromRecents", task, o.toBundle());
            XLog.i("[keys] task " + task + " riaperto sul display " + to + " (fallback)");
        }
    }

    private static void screenshot(int displayId) throws Throwable {
        Context ctx = AltTabHooks.systemContext();
        if (ctx == null) return;
        Class<?> ls  = XposedHelpers.findClass("com.android.server.LocalServices", AltTabHooks.cl);
        Class<?> dmi = XposedHelpers.findClass("android.hardware.display.DisplayManagerInternal", AltTabHooks.cl);
        Object svc = XposedHelpers.callStaticMethod(ls, "getService", dmi);
        Object buf = XposedHelpers.callMethod(svc, "systemScreenshot", displayId);
        if (buf == null) { XLog.i("[keys] screenshot: nessun buffer per il display " + displayId); return; }

        Bitmap hw = (Bitmap) XposedHelpers.callMethod(buf, "asBitmap");
        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
        try { ((android.hardware.HardwareBuffer) XposedHelpers.callMethod(buf, "getHardwareBuffer")).close(); }
        catch (Throwable ignored) {}

        // MediaProvider non apre file in scrittura al processo di sistema: il PNG
        // lo salva l'app del modulo (ShotProvider), a cui lo passiamo via pipe.
        String name = "Screenshot_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "_monitor.png";
        Uri target = Uri.parse("content://" + SHOT_AUTHORITY + "/" + name);
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(target, "w");
        if (pfd == null) { XLog.i("[keys] screenshot: provider del modulo non raggiungibile"); return; }
        try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        XLog.i("[keys] screenshot del display " + displayId + " inviato al modulo: " + name);
        toast(ctx, displayId, "Screenshot saved to Pictures/Screenshots");
    }

    private static final String SHOT_AUTHORITY = "io.maurizio.hyperdesktop.shots";

    private static void toast(Context ctx, int displayId, String msg) {
        try {
            Display d = ctx.getSystemService(DisplayManager.class).getDisplay(displayId);
            Context dc = d != null ? ctx.createDisplayContext(d) : ctx;
            Toast.makeText(dc, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private DesktopShortcuts() {}
}
