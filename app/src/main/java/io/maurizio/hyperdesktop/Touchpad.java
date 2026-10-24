/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * Telefono come touchpad (system_server).
 *
 * Spegnere il pannello (SurfaceControl, power mode OFF) su questo telefono
 * addormenta anche il sensore touch. Quindi, se l'opzione e' attiva, PhoneScreen
 * lascia il pannello ACCESO ma NERO: questa finestra opaca copre tutto il display
 * del telefono (barre comprese), chiede la luminosita' minima (sul pannello OLED
 * il nero e' comunque spento) e trasforma i tocchi in un mouse virtuale
 * (VirtualMouse) sul monitor:
 *
 *   un dito                    -> muove il puntatore
 *   tocco                      -> clic
 *   tocco, poi tocca e muovi   -> trascina
 *   due dita                   -> scorrimento (naturale, come sul telefono)
 *   tocco con due dita         -> clic destro
 *   tre dita                   -> telefono di nuovo normale
 *
 * La finestra non prende mai il focus: tastiera e scorciatoie restano sul monitor.
 * Esiste solo mentre PhoneScreen considera il telefono "spento"; tutto gira sul
 * thread di PhoneScreen (anche i tocchi: la finestra e' aggiunta da quel thread).
 */
final class Touchpad {

    /** px del telefono -> unita' del mouse per ogni punto di sensibilita' (1..20); poi agisce l'accelerazione di sistema. */
    private static final float GAIN_PER_STEP = 0.5f;
    private static final float TAP_SLOP      = 30f;   // px: oltre e' un movimento, non un tocco
    private static final long  TAP_MS        = 250;
    private static final long  TWO_TAP_MS    = 350;
    private static final long  DOUBLE_TAP_MS = 300;   // tocco + nuovo tocco entro questo tempo = trascina
    private static final float SCROLL_STEP   = 40f;   // px di dita per uno scatto di rotella
    private static final long  HINT_MS       = 15000;
    private static final int   LOG_GESTURES  = 6;     // diagnostica: riepilogo dei primi gesti di ogni sessione
    private static final long  CHECK_MS      = 1200;

    private static WindowManager wm;
    private static View root;
    private static VirtualMouse mouse;
    private static float gain = GAIN_PER_STEP * Prefs.SENS_DEFAULT;

    // ------------------------------------------------------------------
    static boolean isShown() { return root != null; }

    /** Mostra lo schermo nero con il touchpad. false = non disponibile (PhoneScreen spegne il pannello). */
    static boolean show(final Context sys) {
        if (root != null) return true;
        int ext = ExternalDisplayGate.externalId;
        if (ext <= 0) return false;
        DisplayManager dm = sys.getSystemService(DisplayManager.class);
        Display phone = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) return false;

        VirtualMouse m = VirtualMouse.open(sys, ext);
        if (m == null) return false;
        reloadSettings();

        // TYPE_SYSTEM_ERROR sta sopra barra di stato, barra di navigazione e pannello
        // notifiche (serve il permesso INTERNAL_SYSTEM_WINDOW, che system_server ha).
        @SuppressWarnings("deprecation")
        int[] types = { WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY };
        for (int type : types) {
            try {
                Context wctx = sys.createDisplayContext(phone).createWindowContext(phone, type, null);
                WindowManager w = wctx.getSystemService(WindowManager.class);
                if (w == null) continue;
                View v = buildView(wctx);
                w.addView(v, layoutParams(type));
                // Movimenti consegnati appena arrivano, non raggruppati al prossimo
                // fotogramma: lo schermo nero e fermo si aggiorna di rado (LTPO), e
                // i movimenti arriverebbero a scatti.
                v.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER);
                wm = w; root = v; mouse = m;
                resetGesture();
                XLog.i("[touchpad] attivo: schermo del telefono nero, tocchi -> puntatore sul display " + ext
                        + " (finestra tipo " + type + ", telefono a " + Math.round(phone.getRefreshRate()) + " Hz)");
                v.postDelayed(new Runnable() {
                    @Override public void run() { if (mouse != null) mouse.check(sys); }
                }, CHECK_MS);
                return true;
            } catch (Throwable t) {
                XLog.e("[touchpad] finestra tipo " + type + " non aggiunta", t);
            }
        }
        m.close();
        return false;
    }

    /** Rilegge la sensibilita' dalle impostazioni (anche mentre il touchpad e' attivo). */
    static void reloadSettings() {
        gain = GAIN_PER_STEP * Prefs.touchpadSensitivity();
    }

    /** Toglie lo schermo nero e il mouse virtuale (rilascia un eventuale trascinamento). */
    static void hide() {
        if (root == null && mouse == null) return;
        try { if (wm != null && root != null) wm.removeViewImmediate(root); }
        catch (Throwable t) { XLog.e("[touchpad] rimozione della finestra fallita", t); }
        try { if (mouse != null) mouse.close(); }
        catch (Throwable t) { XLog.e("[touchpad] chiusura del mouse virtuale fallita", t); }
        root = null; wm = null; mouse = null;
        resetGesture();
        XLog.i("[touchpad] chiuso");
    }

    // ------------------------------------------------------------------
    // finestra
    // ------------------------------------------------------------------
    private static WindowManager.LayoutParams layoutParams(int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE        // tastiera e focus restano sul monitor
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("HyperDesktop Touchpad");
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);                                  // copre anche le barre di sistema
        // Niente luminosita' forzata al minimo: su questo telefono un pannello a
        // luminosita' 0 manda il sensore touch in modalita' a basso consumo (pochi
        // tocchi al secondo). Sul pannello OLED il nero e' spento a qualsiasi luminosita'.
        try {
            // nessun limite all'esclusione dai gesti di sistema (indietro dai bordi)
            int f = XposedHelpers.getStaticIntField(WindowManager.LayoutParams.class,
                    "PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION");
            XposedHelpers.setIntField(lp, "privateFlags", XposedHelpers.getIntField(lp, "privateFlags") | f);
        } catch (Throwable ignored) {}
        return lp;
    }

    private static View buildView(Context ctx) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(Color.BLACK);

        final TextView hint = new TextView(ctx);
        hint.setText(hintText());
        hint.setTextColor(0xFF7A7A7A);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.25f);
        frame.addView(hint, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        hint.postDelayed(new Runnable() {
            @Override public void run() {
                hint.animate().alpha(0f).setDuration(800).withEndAction(new Runnable() {
                    @Override public void run() { hint.setVisibility(View.GONE); }
                });
            }
        }, HINT_MS);

        frame.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                try {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.requestUnbufferedDispatch(ev);
                    onTouchEvent(ev);
                } catch (Throwable t) { XLog.e("[touchpad] gestione del tocco", t); }
                return true;   // i tocchi non arrivano mai alle app sotto
            }
        });
        // tutto lo schermo escluso dai gesti "indietro" dai bordi (dove il sistema lo rispetta)
        frame.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                try { v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t))); }
                catch (Throwable ignored) {}
            }
        });
        return frame;
    }

    private static String hintText() {
        if ("it".equals(Locale.getDefault().getLanguage())) {
            return "Touchpad\n\n"
                 + "Muovi: un dito  ·  Clic: tocco\n"
                 + "Scorri: due dita  ·  Clic destro: tocco con due dita\n"
                 + "Trascina: tocca, poi tocca e muovi\n\n"
                 + "Telefono normale: tre dita o Win+P";
        }
        return "Touchpad\n\n"
             + "Move: one finger  ·  Click: tap\n"
             + "Scroll: two fingers  ·  Right click: two-finger tap\n"
             + "Drag: tap, then touch and move\n\n"
             + "Phone back: three fingers or Win+P";
    }

    // ------------------------------------------------------------------
    // gesti
    // ------------------------------------------------------------------
    private static final int IDLE = 0, POINT = 1, SCROLL = 2, DONE = 3;

    private static int     mode = IDLE;
    private static int     activeId;
    private static float   lastX, lastY, downX, downY;
    private static float   accX, accY, accV, accH;
    private static float   twoStartX, twoStartY;
    private static long    downTime, twoDownTime, lastTapTime;
    private static boolean moved, twoMoved, dragArmed, dragging;
    // diagnostica del gesto in corso
    private static int     gestures, gMoves, gSent;
    private static float   gDist;

    private static void resetGesture() {
        mode = IDLE; dragging = false; dragArmed = false; lastTapTime = 0;
        accX = accY = accV = accH = 0;
        gestures = 0;
    }

    private static void logGesture(String how, long t) {
        if (++gestures > LOG_GESTURES) return;
        long ms = t - downTime;
        XLog.i("[touchpad] gesto " + gestures + ": " + how + ", " + ms + " ms, " + gMoves + " movimenti"
                + (ms > 0 ? " (" + (gMoves * 1000 / ms) + "/s)" : "")
                + ", dito " + (int) gDist + " px -> mouse " + gSent + " unita' (guadagno x" + gain + ")");
    }

    /** Muove il puntatore del delta del dito (px del telefono). */
    private static void movePointer(VirtualMouse m, float dx, float dy) {
        gMoves++;
        gDist += (float) Math.hypot(dx, dy);
        accX += dx * gain; accY += dy * gain;
        int ix = (int) accX, iy = (int) accY;
        if (ix != 0 || iy != 0) {
            accX -= ix; accY -= iy;
            gSent += Math.abs(ix) + Math.abs(iy);
            m.move(ix, iy);
        }
    }

    private static void onTouchEvent(MotionEvent ev) {
        VirtualMouse m = mouse;
        if (m == null) return;
        long t = ev.getEventTime();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = POINT;
                activeId = ev.getPointerId(0);
                downX = lastX = ev.getX(); downY = lastY = ev.getY();
                downTime = t; moved = false; dragging = false;
                accX = accY = 0;
                gMoves = 0; gSent = 0; gDist = 0;
                dragArmed = lastTapTime != 0 && t - lastTapTime <= DOUBLE_TAP_MS;
                break;

            case MotionEvent.ACTION_POINTER_DOWN: {
                int n = ev.getPointerCount();
                if (n >= 3) {                          // tre dita: ridai il telefono
                    mode = DONE;
                    if (dragging) { m.release(VirtualMouse.LEFT); dragging = false; }
                    PhoneScreen.restoreFromTouchpad();
                    break;
                }
                if (n == 2 && mode == POINT && !dragging) {
                    mode = SCROLL;
                    twoDownTime = t; twoMoved = false;
                    twoStartX = lastX = centroidX(ev);
                    twoStartY = lastY = centroidY(ev);
                    accV = accH = 0;
                }
                break;
            }

            case MotionEvent.ACTION_MOVE:
                if (mode == POINT) {
                    int i = ev.findPointerIndex(activeId);
                    if (i < 0) break;
                    // anche i campioni intermedi raggruppati nello stesso evento
                    int hs = ev.getHistorySize();
                    for (int h = 0; h <= hs; h++) {
                        float x = h < hs ? ev.getHistoricalX(i, h) : ev.getX(i);
                        float y = h < hs ? ev.getHistoricalY(i, h) : ev.getY(i);
                        float dx = x - lastX, dy = y - lastY;
                        lastX = x; lastY = y;
                        if (!moved && Math.hypot(x - downX, y - downY) > TAP_SLOP) {
                            moved = true;
                            if (dragArmed) { dragging = true; m.press(VirtualMouse.LEFT); }
                        }
                        movePointer(m, dx, dy);
                    }
                } else if (mode == SCROLL && ev.getPointerCount() >= 2) {
                    float cx = centroidX(ev), cy = centroidY(ev);
                    float dx = cx - lastX, dy = cy - lastY;
                    lastX = cx; lastY = cy;
                    if (!twoMoved && Math.hypot(cx - twoStartX, cy - twoStartY) > TAP_SLOP) twoMoved = true;
                    accV += dy; accH += dx;
                    int v = (int) (accV / SCROLL_STEP), h = (int) (accH / SCROLL_STEP);
                    if (v != 0 || h != 0) {
                        accV -= v * SCROLL_STEP; accH -= h * SCROLL_STEP;
                        // naturale: dita giu' = contenuto giu' = rotella su; dita a destra = contenuto a destra
                        m.scroll(v, -h);
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (mode == SCROLL) {
                    if (!twoMoved && t - twoDownTime <= TWO_TAP_MS) m.click(VirtualMouse.RIGHT);
                    mode = DONE;                       // il resto del gesto non muove il puntatore
                } else if (mode == POINT && ev.getPointerId(ev.getActionIndex()) == activeId) {
                    mode = DONE;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mode == POINT && ev.getPointerId(0) == activeId) {
                    // ultimo tratto: tra l'ultimo movimento e il sollevamento del dito
                    float dx = ev.getX() - lastX, dy = ev.getY() - lastY;
                    if (moved && (dx != 0 || dy != 0)) movePointer(m, dx, dy);
                }
                logGesture(dragging ? "trascinamento" : mode == POINT ? (moved ? "movimento" : "tocco")
                        : mode == DONE ? "due/tre dita" : "altro", t);
                if (dragging) {
                    m.release(VirtualMouse.LEFT);
                    lastTapTime = 0;
                } else if (mode == POINT && !moved && t - downTime <= TAP_MS) {
                    m.click(VirtualMouse.LEFT);
                    lastTapTime = t;
                } else {
                    lastTapTime = 0;
                }
                dragging = false;
                mode = IDLE;
                break;

            case MotionEvent.ACTION_CANCEL:            // es. un gesto di sistema si e' preso il tocco
                logGesture("ANNULLATO dal sistema", t);
                if (dragging) m.release(VirtualMouse.LEFT);
                dragging = false;
                mode = IDLE;
                lastTapTime = 0;
                break;
        }
    }

    private static float centroidX(MotionEvent ev) { return (ev.getX(0) + ev.getX(1)) / 2f; }
    private static float centroidY(MotionEvent ev) { return (ev.getY(0) + ev.getY(1)) / 2f; }

    private Touchpad() {}
}
