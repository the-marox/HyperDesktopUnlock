/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Impostazioni del modulo. Gira nel processo dell'app, NON dentro un hook:
 * niente API Xposed qui (da Prefs usa solo costanti di compilazione).
 *
 * Le preferenze sono scritte con MODE_WORLD_READABLE: con xposedsharedprefs
 * LSPosed le rende leggibili a system_server e SystemUI, che le rileggono al
 * momento dell'uso. Densita' e "tieni acceso" valgono per la sessione desktop
 * in corso: dopo averle salvate mandiamo a system_server un broadcast protetto
 * da un permesso con firma, cosi' le riapplica subito.
 */
public class SettingsActivity extends Activity {

    /**
     * Per processo: ContextImpl tiene in cache le preferenze e controlla il modo
     * solo alla prima apertura, quindi l'esito del primo tentativo va ricordato.
     */
    private static Boolean sModuleActive;

    private SharedPreferences prefs;
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = openPrefs();

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(8), pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        header(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        if (prefs == null) { note(getString(R.string.status_unavailable)); return; }
        note(getString(Boolean.TRUE.equals(sModuleActive) ? R.string.status_active : R.string.status_inactive));

        section(R.string.section_dock);
        toggle(R.string.auto_launch_title, R.string.auto_launch_summary,
               Prefs.K_AUTO_LAUNCH, Prefs.D_AUTO_LAUNCH, false);
        textField(R.string.launcher_pkg_title, R.string.launcher_pkg_summary,
                  Prefs.K_LAUNCHER_PKG, Prefs.D_LAUNCHER_PKG);
        toggle(R.string.manage_a11y_title, R.string.manage_a11y_summary,
               Prefs.K_MANAGE_A11Y, Prefs.D_MANAGE_A11Y, false);

        section(R.string.section_phone);
        toggle(R.string.phone_off_title, R.string.phone_off_summary,
               Prefs.K_PHONE_OFF, Prefs.D_PHONE_OFF, false);
        toggle(R.string.keep_awake_title, R.string.keep_awake_summary,
               Prefs.K_KEEP_AWAKE, Prefs.D_KEEP_AWAKE, true);

        section(R.string.section_monitor);
        densityField();

        section(R.string.section_keyboard);
        toggle(R.string.shortcuts_title, 0, Prefs.K_SHORTCUTS, Prefs.D_SHORTCUTS, false);
        note(getString(R.string.shortcuts_list));
    }

    // ------------------------------------------------------------------
    /** @return null se le preferenze non si possono aprire affatto (demone LSPosed che non risponde). */
    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        if (sModuleActive == null) {
            try {
                SharedPreferences sp = getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
                sModuleActive = true;
                return sp;
            } catch (Throwable t) {
                // SecurityException: LSPosed non attivo per il modulo (MODE_WORLD_READABLE vietato).
                // Altro (es. NPE): LSPosed attivo ma senza cartella delle preferenze.
                sModuleActive = false;
            }
        }
        try {
            return getSharedPreferences(Prefs.FILE,
                    sModuleActive ? Context.MODE_WORLD_READABLE : Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Chiede a system_server di rileggere e riapplicare subito le impostazioni. */
    private void applyNow() {
        try { sendBroadcast(new Intent(Prefs.ACTION_APPLY)); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------
    // costruzione della schermata
    // ------------------------------------------------------------------
    private void toggle(int title, int summary, final String key, boolean def, final boolean live) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setPadding(0, dp(12), 0, summary == 0 ? dp(12) : dp(2));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                if (live) {
                    prefs.edit().putBoolean(key, v).commit();   // scritto prima del broadcast
                    applyNow();
                } else {
                    prefs.edit().putBoolean(key, v).apply();
                }
            }
        });
        root.addView(sw, matchWidth());
        if (summary != 0) small(getString(summary));
    }

    private void textField(int title, int summary, final String key, String def) {
        label(title);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(prefs.getString(key, def));
        e.setHint(def);   // campo vuoto = valore predefinito
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString().trim()).apply();
            }
        });
        root.addView(e, matchWidth());
        small(getString(summary));
    }

    /**
     * Densita': salvata solo con "Applica", dopo la validazione (un "3" a meta'
     * digitazione non deve mai arrivare al monitor). Vuoto + Applica = il modulo
     * non tocca piu' la densita'; 0 = valore predefinito del monitor.
     */
    private void densityField() {
        label(R.string.density_title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (prefs.contains(Prefs.K_DENSITY)) e.setText(prefs.getString(Prefs.K_DENSITY, ""));
        row.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button apply = new Button(this);
        apply.setText(R.string.density_apply);
        row.addView(apply, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, matchWidth());
        small(getString(R.string.density_summary));

        final View.OnClickListener onApply = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String s = e.getText().toString().trim();
                hideKeyboard(e);
                if (s.isEmpty()) {
                    prefs.edit().remove(Prefs.K_DENSITY).commit();
                    Toast.makeText(SettingsActivity.this, R.string.density_cleared, Toast.LENGTH_LONG).show();
                    return;
                }
                int dpi;
                try { dpi = Integer.parseInt(s); }
                catch (NumberFormatException ex) { dpi = -1; }
                if (dpi != Prefs.DENSITY_DEFAULT && (dpi < Prefs.DENSITY_MIN || dpi > Prefs.DENSITY_MAX)) {
                    e.setError(getString(R.string.density_invalid));
                    return;
                }
                e.setText(String.valueOf(dpi));
                e.setSelection(e.length());
                prefs.edit().putString(Prefs.K_DENSITY, String.valueOf(dpi)).commit();
                applyNow();
                Toast.makeText(SettingsActivity.this, R.string.density_saved, Toast.LENGTH_LONG).show();
            }
        };
        apply.setOnClickListener(onApply);
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_DONE) { onApply.onClick(v); return true; }
                return false;
            }
        });
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t);
    }

    private void section(int res) {
        TextView t = new TextView(this);
        t.setText(getString(res).toUpperCase());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, dp(28), 0, dp(4));
        t.setAlpha(0.7f);
        root.addView(t);
    }

    private void label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(12), 0, 0);
        root.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, dp(4));
        t.setAlpha(0.85f);
        root.addView(t);
    }

    private void small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(0.65f);
        t.setPadding(0, 0, 0, dp(6));
        root.addView(t);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
