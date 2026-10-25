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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * Telefono come touchpad (system_server).
 *
 * I tocchi si leggono direttamente dal driver del touchscreen (RawTouch): su
 * HyperOS la catena di input annulla il tocco appena il mouse virtuale si muove.
 * La finestra nera serve a oscurare e a bloccare le app sotto; i suoi tocchi
 * sono solo il ripiego se la lettura diretta non parte.
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
 *   due dita che si allargano  -> zoom (Ctrl+rotella, nelle app che lo supportano)
 *   tocco con due dita         -> clic destro
 *   tre dita                   -> telefono di nuovo normale
 *
 * Tastiera sul telefono (opzione): finche' il touchpad e' attivo, ExternalDisplayGate
 * fa rispondere al monitor "mostra la tastiera sul display principale" (politica
 * IME FALLBACK_DISPLAY, solo in memoria, nessuna impostazione salvata). Se senza
 * tastiera fisica si tocca un campo di testo sul monitor, la tastiera compare sul
 * telefono: qui ne leggiamo l'altezza e accorciamo la finestra nera per lasciarla
 * visibile e toccabile.
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
    private static final float PINCH_SLOP    = 40f;   // px: la distanza tra le dita cambia di piu' = pizzico
    private static final float PINCH_STEP    = 60f;   // px di variazione della distanza per uno scatto di zoom
    private static final long  HINT_MS       = 15000;
    private static final int   LOG_GESTURES  = 6;     // diagnostica: riepilogo dei primi gesti di ogni sessione
    private static final long  CHECK_MS      = 1200;
    private static final long  IME_POLL_MS   = 300;

    private static WindowManager wm;
    private static WindowManager.LayoutParams wlp;
    private static View root;
    private static volatile VirtualMouse mouse;
    /** Lettura diretta del touchscreen; null = si usano i tocchi della finestra. */
    private static volatile RawTouch raw;
    /** Thread dei tocchi e della finestra (quello di PhoneScreen). */
    private static volatile Handler ui;
    /**
     * Thread a parte per leggere l'altezza della tastiera: la lettura prende il lock
     * globale del WindowManager e, sul thread dei tocchi, li faceva arrivare a scatti.
     */
    private static Handler imeHandler;
    /** Cambia a ogni show/hide: i controlli della sessione precedente si fermano. */
    private static volatile int session;
    /** Stato della tastiera applicato alla finestra (vedi ImePoll): 0 = nessuna. */
    private static volatile int imeKey;
    private static volatile Object wms, wmLock;
    private static volatile boolean imeErrorLogged;
    private static volatile Object wmInternal;

    /** Letti dall'hook di getImePolicy, sotto il lock del WindowManager: solo campi, niente I/O. */
    static volatile boolean active;
    static volatile boolean keyboardOnPhone = true;
    /** L'altezza della tastiera si legge su questa build: senza, non la si sposta sul telefono. */
    static volatile boolean imeProbeOk;
    /**
     * Serve davvero la tastiera a schermo: nessuna tastiera fisica (o l'opzione di
     * sistema "mostra anche con la tastiera fisica"). Con una tastiera fisica la
     * tastiera NON va spostata sul telefono: i tasti fisici passano comunque dal
     * servizio della tastiera, e dietro lo schermo nero arrivavano a raffiche o si
     * perdevano.
     */
    static volatile boolean softKeyboardWanted = true;
    private static volatile boolean lastSoftLogged = true;
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
        // Nel try solo l'aggiunta della finestra: cosi' un errore non lascia mai una
        // finestra nera orfana, e non se ne aggiungono due.
        @SuppressWarnings("deprecation")
        int[] types = { WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY };
        WindowManager w = null;
        WindowManager.LayoutParams lp = null;
        View v = null;
        int addedType = 0;
        for (int type : types) {
            WindowManager tw = null;
            View tv = null;
            try {
                Context wctx = sys.createDisplayContext(phone).createWindowContext(phone, type, null);
                tw = wctx.getSystemService(WindowManager.class);
                if (tw == null) continue;
                tv = buildView(wctx);
                WindowManager.LayoutParams tlp = layoutParams(type);
                tw.addView(tv, tlp);
                w = tw; v = tv; lp = tlp; addedType = type;
                break;
            } catch (Throwable t) {
                XLog.e("[touchpad] finestra tipo " + type + " non aggiunta", t);
                if (tw != null && tv != null && tv.isAttachedToWindow()) {
                    try { tw.removeViewImmediate(tv); } catch (Throwable ignored) {}
                }
            }
        }
        if (v == null) { m.close(); return false; }

        wm = w; wlp = lp; root = v; mouse = m;
        resetGesture();
        imeKey = 0;
        padBottom = Integer.MAX_VALUE;
        ExternalDisplayGate.imeLogged = 0;   // diagnostica: righe [ime] di nuovo per questa sessione
        lastSoftLogged = true;
        updateSoftKeyboardWanted();          // prima che l'hook possa spostare la tastiera
        imeErrorLogged = false;
        active = true;
        try {
            // Movimenti consegnati appena arrivano, non raggruppati al prossimo
            // fotogramma: lo schermo nero e fermo si aggiorna di rado (LTPO), e
            // i movimenti arriverebbero a scatti.
            v.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER);
            ui = new Handler(Looper.myLooper());
            int s = ++session;
            imeHandler().postDelayed(new ImePoll(s), IME_POLL_MS);
            final VirtualMouse mine = m;
            v.postDelayed(new Runnable() {
                private int tries;
                @Override public void run() {
                    if (mouse != mine) return;                       // touchpad chiuso o riaperto
                    if (tries == 0) mine.check(sys); else mine.checkKeys(sys);
                    if (!mine.keysPending()) return;
                    Handler h = ui;
                    if (++tries <= 3 && h != null) h.postDelayed(this, CHECK_MS);
                    else XLog.i("[touchpad] tasto Ctrl virtuale mai visto dal sistema: il pizzico non fa lo zoom");
                }
            }, CHECK_MS);
            raw = RawTouch.start(sys, phone, RAW_LISTENER);
            if (raw == null) XLog.i("[touchpad] uso i tocchi della finestra (ripiego)");
            XLog.i("[touchpad] attivo: schermo del telefono nero, tocchi -> puntatore sul display " + ext
                    + " (finestra tipo " + addedType + ", telefono a " + Math.round(phone.getRefreshRate()) + " Hz)");
            return true;
        } catch (Throwable t) {
            XLog.e("[touchpad] avvio incompleto, chiudo", t);
            hide();
            return false;
        }
    }

    /** Rilegge la sensibilita' dalle impostazioni (anche mentre il touchpad e' attivo). */
    static void reloadSettings() {
        gain = GAIN_PER_STEP * Prefs.touchpadSensitivity();
        keyboardOnPhone = Prefs.phoneKeyboard();
    }

    /** Toglie lo schermo nero e il mouse virtuale (rilascia un eventuale trascinamento). */
    static void hide() {
        if (root == null && mouse == null) return;
        active = false;
        session++;   // ferma il controllo della tastiera di questa sessione
        RawTouch r = raw;
        raw = null;
        if (r != null) r.stop();   // prima del mouse: il thread del driver lo usa
        try { if (wm != null && root != null) wm.removeViewImmediate(root); }
        catch (Throwable t) { XLog.e("[touchpad] rimozione della finestra fallita", t); }
        try { if (mouse != null) mouse.close(); }
        catch (Throwable t) { XLog.e("[touchpad] chiusura del mouse virtuale fallita", t); }
        root = null; wm = null; wlp = null; mouse = null;
        resetGesture();
        XLog.i("[touchpad] chiuso");
    }

    // ------------------------------------------------------------------
    // tastiera sul telefono
    // ------------------------------------------------------------------
    /** Gira sul thread IME; cambia la finestra solo sul thread dei tocchi. */
    private static final class ImePoll implements Runnable {
        private final int s;
        ImePoll(int s) { this.s = s; }

        @Override public void run() {
            if (s != session) return;
            try {
                updateSoftKeyboardWanted();
                // Sempre, anche con l'opzione spenta: se una tastiera e' comunque sul
                // telefono (es. opzione appena spenta), non deve restare sotto il nero.
                final int top = imeTop();                       // finestra della tastiera
                final int h = top < 0 ? imeVisibleHeight() : 0; // riserva: altezza dagli inset
                imeProbeOk = top >= 0 || h >= 0;
                final int key = top > 0 ? top : (h > 0 ? -h : 0);
                Handler u = ui;
                if (key != imeKey && u != null) {
                    u.post(new Runnable() {
                        @Override public void run() {
                            if (s != session) return;
                            try { applyIme(key, top, h); }
                            catch (Throwable t) { XLog.e("[touchpad] adattamento alla tastiera fallito", t); }
                        }
                    });
                }
            } catch (Throwable t) { XLog.e("[touchpad] controllo della tastiera fallito", t); }
            if (s == session) imeHandler().postDelayed(this, IME_POLL_MS);
        }
    }

    private static synchronized Handler imeHandler() {
        if (imeHandler == null) {
            HandlerThread t = new HandlerThread("HyperDesktop-ime");
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread th, Throwable e) {
                    try { XLog.e("[touchpad] eccezione non gestita sul thread della tastiera", e); } catch (Throwable ignored) {}
                    imeProbeOk = false;   // senza controllo, la tastiera non va spostata sul telefono
                    synchronized (Touchpad.class) { imeHandler = null; }
                }
            });
            t.start();
            imeHandler = new Handler(t.getLooper());
        }
        return imeHandler;
    }

    /** Come decidono le tastiere: tastiera fisica collegata e non nascosta = niente tastiera a schermo. */
    private static void updateSoftKeyboardWanted() {
        boolean wanted = true;
        try {
            Context sys = sysContext();
            if (sys != null) {
                android.content.res.Configuration c = sys.getResources().getConfiguration();
                boolean hard = c.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY
                        && c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO;
                boolean showAnyway = android.provider.Settings.Secure.getInt(
                        sys.getContentResolver(), "show_ime_with_hard_keyboard", 0) == 1;
                wanted = !hard || showAnyway;
            }
        } catch (Throwable ignored) {}
        softKeyboardWanted = wanted;
        if (wanted != lastSoftLogged) {
            lastSoftLogged = wanted;
            XLog.i(wanted ? "[ime] nessuna tastiera fisica: la tastiera a schermo va sul telefono"
                          : "[ime] tastiera fisica collegata: la tastiera resta dov'e' (niente spostamento)");
        }
    }

    private static Context sysContext() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) { return null; }
    }

    /**
     * Dove inizia la tastiera sul telefono, letto dalla sua finestra (y in px;
     * 0 = nessuna tastiera visibile; -1 = non leggibile). Funziona anche quando il
     * campo di testo e' sul monitor, a differenza degli inset del telefono.
     */
    private static int imeTop() {
        try {
            if (wms == null) {
                Object w = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "window");
                wmLock = XposedHelpers.getObjectField(w, "mGlobalLock");
                wms = w;
            }
            synchronized (wmLock) {
                Object rootC = XposedHelpers.getObjectField(wms, "mRoot");
                Object dc = XposedHelpers.callMethod(rootC, "getDisplayContent", Display.DEFAULT_DISPLAY);
                if (dc == null) return 0;
                // Su Android 17 DisplayContent non ha piu' mInputMethodWindow: si scorrono
                // le finestre del telefono (forAllWindows, presente da Android 8) e si
                // prende quella di tipo TYPE_INPUT_METHOD visibile.
                final Object[] found = { null };
                final boolean[] unreadable = { false };
                java.util.function.Consumer<Object> c = new java.util.function.Consumer<Object>() {
                    @Override public void accept(Object ws) {
                        if (found[0] != null) return;
                        try {
                            WindowManager.LayoutParams a = (WindowManager.LayoutParams) XposedHelpers.getObjectField(ws, "mAttrs");
                            if (a.type != WindowManager.LayoutParams.TYPE_INPUT_METHOD) return;
                            if ((Boolean) XposedHelpers.callMethod(ws, "isVisible")) found[0] = ws;
                        } catch (Throwable t) { unreadable[0] = true; }
                    }
                };
                XposedHelpers.callMethod(dc, "forAllWindows", c, true);
                Object ime = found[0];
                if (ime == null) {
                    if (unreadable[0]) throw new IllegalStateException("finestre non leggibili");   // -> riserva
                    return 0;
                }
                Rect f = (Rect) XposedHelpers.callMethod(ime, "getFrame");
                int top = f.top;
                try {   // parte trasparente in cima alla finestra della tastiera
                    Rect ci = (Rect) XposedHelpers.getObjectField(ime, "mGivenContentInsets");
                    if (ci != null) top += ci.top;
                } catch (Throwable ignored) {}
                return Math.max(1, top);
            }
        } catch (Throwable t) {
            if (!imeErrorLogged) { imeErrorLogged = true; XLog.e("[touchpad] finestra della tastiera non leggibile, uso gli inset", t); }
            return -1;
        }
    }

    /** Altezza visibile della tastiera sul display del telefono (0 = chiusa, -1 = non leggibile). */
    private static int imeVisibleHeight() {
        try {
            if (wmInternal == null) {
                ClassLoader cl = PhoneScreen.classLoader();
                wmInternal = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.LocalServices", cl), "getService",
                    XposedHelpers.findClass("com.android.server.wm.WindowManagerInternal", cl));
            }
            if (wmInternal == null) return -1;
            return (Integer) XposedHelpers.callMethod(wmInternal, "getInputMethodWindowVisibleHeight",
                    Display.DEFAULT_DISPLAY);
        } catch (Throwable t) {
            if (!imeErrorLogged) { imeErrorLogged = true; XLog.e("[touchpad] altezza della tastiera non leggibile", t); }
            return -1;
        }
    }

    /** Accorcia la finestra nera sopra la tastiera, o la riporta a tutto schermo. */
    private static void applyIme(int key, int top, int h) {
        if (wm == null || wlp == null || root == null || key == imeKey) return;
        android.view.WindowMetrics wmx0 = wm.getCurrentWindowMetrics();
        int screenH0 = wmx0.getBounds().height();
        if (top > 0) {
            wlp.height = Math.max(screenH0 / 4, Math.min(top, screenH0));
        } else if (h > 0) {
            android.view.WindowMetrics wmx = wmx0;
            int screenH = screenH0;
            // L'altezza riportata parte dal bordo superiore della barra di navigazione.
            // Una finestra sopra la barra non ne riceve gli inset: si leggono quelli
            // del display (WindowMetrics), con quelli della finestra come riserva.
            int nav = 0;
            try { nav = wmx.getWindowInsets().getInsets(WindowInsets.Type.navigationBars()).bottom; }
            catch (Throwable ignored) {}
            WindowInsets wi = root.getRootWindowInsets();
            if (wi != null) nav = Math.max(nav, wi.getInsets(WindowInsets.Type.navigationBars()).bottom);
            wlp.height = Math.max(screenH / 4, screenH - nav - h);
        } else {
            wlp.height = WindowManager.LayoutParams.MATCH_PARENT;
        }
        wm.updateViewLayout(root, wlp);
        imeKey = key;   // solo se riuscito: altrimenti si riprova al prossimo controllo
        padBottom = wlp.height > 0 ? wlp.height : Integer.MAX_VALUE;
        XLog.i(key != 0 ? "[touchpad] tastiera sul telefono (" + (top > 0 ? "inizia a y=" + top : "alta " + h + " px")
                              + "): touchpad ridotto a " + wlp.height + " px"
                        : "[touchpad] tastiera chiusa: touchpad a tutto schermo");
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
                    RawTouch r = raw;
                    if (r == null || !r.isRunning()) onWindowEvent(ev);   // altrimenti legge il driver
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
                 + "Zoom: allarga o stringi due dita\n"
                 + "Trascina: tocca, poi tocca e muovi\n\n"
                 + "Telefono normale: tre dita o Win+P";
        }
        return "Touchpad\n\n"
             + "Move: one finger  ·  Click: tap\n"
             + "Scroll: two fingers  ·  Right click: two-finger tap\n"
             + "Zoom: spread or pinch two fingers\n"
             + "Drag: tap, then touch and move\n\n"
             + "Phone back: three fingers or Win+P";
    }

    // ------------------------------------------------------------------
    // gesti: motore a "fotogrammi" (dita appoggiate e posizioni in px del telefono).
    // Lo alimenta la lettura diretta del driver (RawTouch) o, in ripiego, la finestra.
    // Un solo thread alla volta: quello di RawTouch, oppure quello della finestra.
    // ------------------------------------------------------------------
    private static final int IDLE = 0, POINT = 1, SCROLL = 2, DONE = 3;
    private static final int MAXP = 10;

    private static int     mode = IDLE;
    private static int     primaryId, twoA, twoB;
    private static float   lastX, lastY, downX, downY;
    private static float   accX, accY, accV, accH;
    private static float   twoStartX, twoStartY;
    private static long    downTime, twoDownTime, lastTapTime;
    private static boolean moved, twoMoved, dragArmed, dragging;
    private static int     prevN;
    // due dita: scorrimento o pizzico, deciso una volta per gesto
    private static boolean twoDecided, pinching, wasPinch;
    private static float   twoStartDist, lastDist, accZ;
    private static int     zoomSteps;   // scatti di zoom gia' mandati in questo pizzico

    // diagnostica del gesto in corso
    private static int     gestures, gFrames, gSent, gPalm0;
    private static float   gDist;
    private static volatile String gSource = "finestra";

    /**
     * Fondo dell'area del touchpad sul telefono (px): sotto c'e' la tastiera.
     * Il driver vede TUTTE le dita, anche quelle sulla tastiera: un dito che si
     * appoggia sotto questo limite non e' del touchpad (altrimenti ogni tasto
     * diventerebbe un clic sul monitor e toglierebbe il focus al campo di testo).
     */
    private static volatile int padBottom = Integer.MAX_VALUE;

    // dita ignorate perche' appoggiate sulla tastiera (solo thread del driver)
    private static final int[] skipIds = new int[MAXP];
    private static int skipN;
    private static final int[] fIds = new int[MAXP];
    private static final float[] fX = new float[MAXP], fY = new float[MAXP];
    private static final int[] seenIds = new int[MAXP];
    private static int seenN;

    private static final RawTouch.Listener RAW_LISTENER = new RawTouch.Listener() {
        @Override public void onFrame(long t, int n, int[] ids, float[] xs, float[] ys) {
            try {
                gSource = "driver";
                int limit = padBottom, keep = 0, newSkipN = 0;
                int[] newSkip = new int[MAXP];
                for (int i = 0; i < n && i < MAXP; i++) {
                    int id = ids[i];
                    boolean known = indexOf(seenIds, seenN, id) >= 0;
                    boolean skip = known ? indexOf(skipIds, skipN, id) >= 0 : ys[i] >= limit;
                    if (skip) { newSkip[newSkipN++] = id; continue; }
                    fIds[keep] = id; fX[keep] = xs[i]; fY[keep] = ys[i]; keep++;
                }
                System.arraycopy(newSkip, 0, skipIds, 0, newSkipN);
                skipN = newSkipN;
                seenN = Math.min(n, MAXP);
                System.arraycopy(ids, 0, seenIds, 0, seenN);
                frame(t, keep, fIds, fX, fY);
            }
            catch (Throwable e) { XLog.e("[touchpad] gestione del gesto (driver)", e); }
        }
        @Override public void onReset(long t) {
            skipN = 0; seenN = 0;
            try { resetStream(t, "interrotto"); } catch (Throwable ignored) {}
        }
    };

    private static void resetGesture() {
        mode = IDLE; dragging = false; dragArmed = false; lastTapTime = 0; prevN = 0;
        accX = accY = accV = accH = accZ = 0;
        twoDecided = false; pinching = false; wasPinch = false;   // Ctrl lo rilascia VirtualMouse.close()
        gestures = 0;
    }

    private static void logGesture(String how, long t) {
        if (++gestures > LOG_GESTURES) return;
        long ms = t - downTime;
        RawTouch r = raw;
        int palms = r != null ? r.palmReports - gPalm0 : 0;
        XLog.i("[touchpad] gesto " + gestures + " (" + gSource + "): " + how + ", " + ms + " ms, " + gFrames + " campioni"
                + (ms > 0 ? " (" + (gFrames * 1000 / ms) + "/s)" : "")
                + ", dito " + (int) gDist + " px -> mouse " + gSent + " unita' (guadagno x" + gain + ")"
                + (palms > 0 ? ", " + palms + " segnalazioni 'palmo' dal driver" : ""));
    }

    /** Muove il puntatore del delta del dito (px del telefono). */
    private static void movePointer(VirtualMouse m, float dx, float dy) {
        gDist += (float) Math.hypot(dx, dy);
        accX += dx * gain; accY += dy * gain;
        int ix = (int) accX, iy = (int) accY;
        if (ix != 0 || iy != 0) {
            accX -= ix; accY -= iy;
            gSent += Math.abs(ix) + Math.abs(iy);
            m.move(ix, iy);
        }
    }

    private static int indexOf(int[] ids, int n, int id) {
        for (int i = 0; i < n; i++) if (ids[i] == id) return i;
        return -1;
    }

    /** Un fotogramma: n dita appoggiate (id, x, y). n == 0 = tutte sollevate. */
    private static void frame(long t, int n, int[] ids, float[] xs, float[] ys) {
        VirtualMouse m = mouse;
        if (m == null) { prevN = n; return; }

        if (prevN == 0 && n > 0) {                   // primo dito: inizio del gesto
            mode = POINT;
            primaryId = ids[0];
            downX = lastX = xs[0]; downY = lastY = ys[0];
            downTime = t; moved = false; dragging = false; wasPinch = false;
            accX = accY = 0;
            gFrames = 0; gSent = 0; gDist = 0;
            RawTouch r = raw;
            gPalm0 = r != null ? r.palmReports : 0;
            dragArmed = lastTapTime != 0 && t - lastTapTime <= DOUBLE_TAP_MS;
        }
        if (n > 0) gFrames++;

        if (n >= 3 && mode != DONE) {                // tre dita: ridai il telefono
            mode = DONE;
            endPinch(m);
            if (dragging) { m.release(VirtualMouse.LEFT); dragging = false; }
            PhoneScreen.restoreFromTouchpad();
        } else if (n == 2 && mode == POINT && !dragging) {   // due dita: scorrimento o pizzico
            mode = SCROLL;
            twoA = ids[0]; twoB = ids[1];
            twoDownTime = t; twoMoved = false;
            twoStartX = lastX = (xs[0] + xs[1]) / 2f;
            twoStartY = lastY = (ys[0] + ys[1]) / 2f;
            twoStartDist = lastDist = dist(xs[0], ys[0], xs[1], ys[1]);
            endPinch(m);
            twoDecided = false;
            accV = accH = accZ = 0;
        } else if (n > 0 && n < prevN) {              // un dito sollevato, altri ancora giu'
            if (mode == SCROLL) {
                endPinch(m);
                if (!twoMoved && t - twoDownTime <= TWO_TAP_MS) m.click(VirtualMouse.RIGHT);
                mode = DONE;                          // il resto del gesto non muove il puntatore
            } else if (mode == POINT && indexOf(ids, n, primaryId) < 0) {
                mode = DONE;
            }
        } else if (n > 0) {                           // movimento
            if (mode == POINT) {
                int i = indexOf(ids, n, primaryId);
                if (i >= 0) {
                    float x = xs[i], y = ys[i];
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x; lastY = y;
                    if (!moved && Math.hypot(x - downX, y - downY) > TAP_SLOP) {
                        moved = true;
                        if (dragArmed) { dragging = true; m.press(VirtualMouse.LEFT); }
                    }
                    movePointer(m, dx, dy);
                }
            } else if (mode == SCROLL) {
                int a = indexOf(ids, n, twoA), b = indexOf(ids, n, twoB);
                if (a >= 0 && b >= 0) twoFingers(m, xs[a], ys[a], xs[b], ys[b]);
            }
        }

        if (n == 0 && prevN > 0) {                    // tutte le dita sollevate: fine del gesto
            endPinch(m);
            logGesture(wasPinch ? "zoom (pizzico)" : dragging ? "trascinamento"
                    : mode == POINT ? (moved ? "movimento" : "tocco")
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
        }
        prevN = n;
    }

    /**
     * Due dita appoggiate. Si decide una volta per gesto: scorrimento se le dita si
     * muovono insieme, pizzico se cambia la distanza tra loro. Il pizzico tiene
     * premuto Ctrl e manda scatti di rotella: Ctrl+rotella e' lo zoom nelle app che
     * lo supportano (browser, documenti). Prima della decisione non si scorre: lo
     * spostamento si accumula e parte appena e' chiaro che e' uno scorrimento.
     */
    private static void twoFingers(VirtualMouse m, float ax, float ay, float bx, float by) {
        float cx = (ax + bx) / 2f, cy = (ay + by) / 2f;
        float dx = cx - lastX, dy = cy - lastY;
        lastX = cx; lastY = cy;
        float d  = dist(ax, ay, bx, by);
        float cm = (float) Math.hypot(cx - twoStartX, cy - twoStartY);   // spostamento del centro
        float dd = Math.abs(d - twoStartDist);                           // variazione della distanza
        // non e' piu' un tocco (clic destro); per la distanza serve la soglia del pizzico:
        // in un tocco con due dita le due dita oscillano e la distanza varia il doppio
        if (!twoMoved && (cm > TAP_SLOP || dd > PINCH_SLOP)) twoMoved = true;

        if (!twoDecided) {
            boolean canPinch = m.hasCtrl();
            if (canPinch && dd > PINCH_SLOP && dd > cm) {
                twoDecided = true;
                pinching = m.ctrl(true);
                if (pinching) { wasPinch = true; lastDist = d; accZ = 0; zoomSteps = 0; }
            } else if (cm > TAP_SLOP && (cm >= dd || !canPinch)) {
                twoDecided = true;
            }
        }

        // Falso pizzico: scorrendo con le dita affiancate, un dito che parte prima
        // dell'altro fa cambiare la distanza. Se poi il centro si sposta piu' della
        // distanza e nessuno zoom e' ancora partito, era uno scorrimento.
        if (pinching && zoomSteps == 0 && cm > dd + TAP_SLOP) {
            endPinch(m);
            wasPinch = false;
            accV = accH = 0;
        }

        if (pinching) {
            accZ += d - lastDist;
            lastDist = d;
            int z = (int) (accZ / PINCH_STEP);
            if (z != 0) {
                accZ -= z * PINCH_STEP;
                zoomSteps += Math.abs(z);
                m.scroll(z, 0);   // dita che si allargano = Ctrl+rotella su = zoom avanti
            }
            return;
        }
        accV += dy; accH += dx;
        if (!twoDecided) return;
        int v = (int) (accV / SCROLL_STEP), h = (int) (accH / SCROLL_STEP);
        if (v != 0 || h != 0) {
            accV -= v * SCROLL_STEP; accH -= h * SCROLL_STEP;
            // naturale: dita giu' = contenuto giu' = rotella su; dita a destra = contenuto a destra
            m.scroll(v, -h);
        }
    }

    /** Fine del pizzico: rilascia Ctrl. */
    private static void endPinch(VirtualMouse m) {
        if (!pinching) return;
        pinching = false;
        if (m != null) m.ctrl(false);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x2 - x1, y2 - y1);
    }

    /** Flusso dei tocchi perso o annullato: chiude il gesto senza clic. */
    private static void resetStream(long t, String why) {
        if (prevN > 0) logGesture(why, t);
        VirtualMouse m = mouse;
        endPinch(m);
        if (dragging && m != null) m.release(VirtualMouse.LEFT);
        dragging = false;
        mode = IDLE;
        prevN = 0;
        lastTapTime = 0;
    }

    // ripiego: i tocchi dalla finestra, convertiti in fotogrammi
    private static final int[]   wIds = new int[MAXP];
    private static final float[] wX = new float[MAXP], wY = new float[MAXP];

    private static void onWindowEvent(MotionEvent ev) {
        gSource = "finestra";
        int a = ev.getActionMasked();
        long t = ev.getEventTime();
        if (a == MotionEvent.ACTION_CANCEL) { resetStream(t, "ANNULLATO dal sistema"); return; }
        int pc = Math.min(ev.getPointerCount(), MAXP);
        if (a == MotionEvent.ACTION_MOVE) {
            int hs = ev.getHistorySize();
            for (int h = 0; h < hs; h++) {
                for (int i = 0; i < pc; i++) {
                    wIds[i] = ev.getPointerId(i); wX[i] = ev.getHistoricalX(i, h); wY[i] = ev.getHistoricalY(i, h);
                }
                frame(ev.getHistoricalEventTime(h), pc, wIds, wX, wY);
            }
        }
        for (int i = 0; i < pc; i++) { wIds[i] = ev.getPointerId(i); wX[i] = ev.getX(i); wY[i] = ev.getY(i); }
        frame(t, pc, wIds, wX, wY);   // tutte le dita alla posizione attuale (anche quella che si solleva)
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            int skip = ev.getActionIndex(), n = 0;
            for (int i = 0; i < pc; i++) {
                if (i == skip) continue;
                wIds[n] = ev.getPointerId(i); wX[n] = ev.getX(i); wY[n] = ev.getY(i); n++;
            }
            frame(t, n, wIds, wX, wY);
        }
    }

    private Touchpad() {}
}
