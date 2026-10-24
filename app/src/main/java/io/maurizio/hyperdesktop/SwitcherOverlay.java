/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Barra di anteprima dell'Alt+Tab sul monitor (system_server), in stile Windows:
 * tenendo premuto Alt compaiono le icone delle app aperte sul monitor, Tab sposta
 * l'evidenziazione, al rilascio di Alt l'app evidenziata va in primo piano.
 *
 * La finestra la aggiunge system_server (uid di sistema) sul display esterno, con
 * un WindowContext legato a quel display, tipo APPLICATION_OVERLAY, non a fuoco e
 * non toccabile: non ruba il focus ne' l'input. TUTTO (creazione e aggiornamento
 * della view, addView/removeView) gira sul solo thread di AltTabHooks, che ha un
 * Looper. Se qualcosa va storto, show() torna false e AltTabHooks ripiega sullo
 * scambio immediato senza anteprima: nessun rischio all'avvio, gira solo quando
 * l'utente preme Alt+Tab a monitor collegato.
 */
final class SwitcherOverlay {

    private static final int BAR_BG    = 0xF02A2A2A;   // grigio molto scuro, quasi opaco
    private static final int SEL_BG    = 0x40FFFFFF;   // evidenziazione chiara
    private static final int TEXT_COL  = 0xFFF2F2F2;

    private static WindowManager wm;
    private static LinearLayout bar;
    private static View[] tiles;
    private static boolean added;

    /** @return true se la barra e' stata mostrata (in tal caso AltTabHooks non sposta subito l'app). */
    static boolean show(int displayId, List<AltTabHooks.Task> items, int selected) {
        hide();
        if (items == null || items.size() < 2) return false;
        try {
            Context sys = AltTabHooks.systemContext();
            if (sys == null) return false;
            DisplayManager dm = sys.getSystemService(DisplayManager.class);
            Display disp = dm != null ? dm.getDisplay(displayId) : null;
            if (disp == null) return false;

            Context dctx = sys.createDisplayContext(disp);
            Context wctx = dctx.createWindowContext(disp, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            wm = wctx.getSystemService(WindowManager.class);
            if (wm == null) return false;

            float d = dctx.getResources().getDisplayMetrics().density;
            int screenW = wm.getCurrentWindowMetrics().getBounds().width();
            int perRow = Math.max(2, (int) ((screenW * 0.9f - px(2 * BAR_PAD, d)) / px(TILE_W, d)));
            bar = buildBar(dctx, items, selected, d, perRow);

            // Dimensioni esatte, non WRAP_CONTENT: per le finestre WRAP_CONTENT Android
            // parte da una larghezza "da dialogo" e la barra veniva tagliata a destra.
            bar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                bar.getMeasuredWidth(),
                bar.getMeasuredHeight(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            lp.setTitle("HyperDesktop Switcher");

            wm.addView(bar, lp);
            added = true;
            XLog.i("[switcher] barra mostrata sul display " + displayId + " (" + items.size() + " app)");
            return true;
        } catch (Throwable t) {
            XLog.e("[switcher] impossibile mostrare la barra, ripiego sullo scambio immediato", t);
            cleanup();
            return false;
        }
    }

    /** Sposta l'evidenziazione senza ricostruire la barra. */
    static void updateSelection(int selected) {
        try {
            if (tiles == null) return;
            for (int i = 0; i < tiles.length; i++) {
                tiles[i].setBackground(i == selected ? selectedBg(tiles[i].getResources()
                        .getDisplayMetrics().density) : null);
            }
        } catch (Throwable t) { XLog.e("[switcher] updateSelection fallito", t); }
    }

    static void hide() {
        try {
            if (added && wm != null && bar != null) wm.removeViewImmediate(bar);
        } catch (Throwable t) { XLog.e("[switcher] removeView fallito", t); }
        cleanup();
    }

    private static void cleanup() {
        added = false;
        bar = null;
        tiles = null;
        wm = null;
    }

    // ------------------------------------------------------------------
    private static final int BAR_PAD  = 12;                    // dp
    private static final int TILE_PAD = 10;                    // dp
    private static final int LABEL_W  = 96;                    // dp, piu' largo dell'icona (72)
    private static final int TILE_W   = LABEL_W + 2 * TILE_PAD;

    /** Barra con le tessere; se le app non stanno in una riga, va a capo (come Windows). */
    private static LinearLayout buildBar(Context ctx, List<AltTabHooks.Task> items, int selected,
                                         float d, int perRow) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);             // l'ultima riga, se piu' corta, al centro
        int pad = px(BAR_PAD, d);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BAR_BG);
        bg.setCornerRadius(px(18, d));
        box.setBackground(bg);

        LinearLayout row = null;
        tiles = new View[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AltTabHooks.Task t = items.get(i);
            if (i % perRow == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
            }

            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            int tp = px(TILE_PAD, d);
            tile.setPadding(tp, tp, tp, tp);
            if (i == selected) tile.setBackground(selectedBg(d));

            ImageView icon = new ImageView(ctx);
            int sz = px(72, d);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
            if (t.icon != null) icon.setImageDrawable(t.icon);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            tile.addView(icon, ip);

            TextView label = new TextView(ctx);
            label.setText(t.label != null ? t.label : "");
            label.setTextColor(TEXT_COL);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(px(LABEL_W, d),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lpL.topMargin = px(6, d);
            tile.addView(label, lpL);

            row.addView(tile);
            tiles[i] = tile;
        }
        return box;
    }

    private static Drawable selectedBg(float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(SEL_BG);
        g.setCornerRadius(px(12, d));
        return g;
    }

    private static int px(int dp, float density) {
        return Math.round(dp * density);
    }

    private SwitcherOverlay() {}
}
