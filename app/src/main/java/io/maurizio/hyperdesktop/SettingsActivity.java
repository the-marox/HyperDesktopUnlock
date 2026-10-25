/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Impostazioni del modulo. Gira nel processo dell'app, NON dentro un hook:
 * niente API Xposed qui (da Prefs usa solo costanti di compilazione).
 *
 * Le preferenze sono scritte con MODE_WORLD_READABLE: con xposedsharedprefs
 * LSPosed le rende leggibili a system_server e SystemUI, che le rileggono al
 * momento dell'uso. Le opzioni che valgono per la sessione desktop in corso
 * (densita', risoluzione, frequenza, tieni acceso) vengono riapplicate subito
 * tramite un broadcast protetto da un permesso con firma.
 */
public class SettingsActivity extends Activity {

    private static final String RELEASES_API  =
        "https://api.github.com/repos/the-marox/HyperDesktopUnlock/releases/latest";
    private static final String RELEASES_PAGE =
        "https://github.com/the-marox/HyperDesktopUnlock/releases";

    /**
     * Per processo: ContextImpl tiene in cache le preferenze e controlla il modo
     * solo alla prima apertura, quindi l'esito del primo tentativo va ricordato.
     */
    private static Boolean sModuleActive;

    /** @return valore da salvare; "" per cancellare la chiave; null se non valido. */
    private interface Normalizer { String normalize(String raw); }

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
                  Prefs.K_LAUNCHER_PKG, Prefs.D_LAUNCHER_PKG, Prefs.D_LAUNCHER_PKG);
        textField(R.string.extra_apps_title, R.string.extra_apps_summary,
                  Prefs.K_EXTRA_APPS, "", getString(R.string.extra_apps_hint));
        toggle(R.string.manage_a11y_title, R.string.manage_a11y_summary,
               Prefs.K_MANAGE_A11Y, Prefs.D_MANAGE_A11Y, false);

        section(R.string.section_phone);
        toggle(R.string.phone_off_title, R.string.phone_off_summary,
               Prefs.K_PHONE_OFF, Prefs.D_PHONE_OFF, false);
        toggle(R.string.touchpad_title, R.string.touchpad_summary,
               Prefs.K_TOUCHPAD, Prefs.D_TOUCHPAD, true);
        sliderField(R.string.touchpad_sens_title, R.string.touchpad_sens_summary, Prefs.K_TOUCHPAD_SENS,
                Prefs.SENS_MIN, Prefs.SENS_MAX, Prefs.SENS_DEFAULT);
        toggle(R.string.phone_keyboard_title, R.string.phone_keyboard_summary,
               Prefs.K_PHONE_KEYBOARD, Prefs.D_PHONE_KEYBOARD, true);
        note(getString(R.string.tiles_hint));
        toggle(R.string.keep_awake_title, R.string.keep_awake_summary,
               Prefs.K_KEEP_AWAKE, Prefs.D_KEEP_AWAKE, true);

        section(R.string.section_monitor);
        applyField(R.string.density_title, R.string.density_summary, R.string.density_invalid,
                   Prefs.K_DENSITY, InputType.TYPE_CLASS_NUMBER, NORM_DENSITY);
        Modes modes = loadExternalModes();
        if (modes != null) {
            // monitor noto: si sceglie tra le modalita' supportate
            String[][] res = resolutionOptions(modes);
            choiceField(R.string.resolution_title, R.string.resolution_summary, Prefs.K_RESOLUTION, res[0], res[1]);
            String[][] ref = refreshOptions(modes);
            choiceField(R.string.refresh_title, R.string.refresh_summary, Prefs.K_REFRESH, ref[0], ref[1]);
        } else {
            // nessun monitor mai visto: ripiego sul campo da digitare
            note(getString(R.string.modes_hint));
            applyField(R.string.resolution_title, R.string.resolution_summary, R.string.resolution_invalid,
                       Prefs.K_RESOLUTION, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, NORM_RESOLUTION);
            applyField(R.string.refresh_title, R.string.refresh_summary, R.string.refresh_invalid,
                       Prefs.K_REFRESH, InputType.TYPE_CLASS_NUMBER, NORM_REFRESH);
        }
        toggle(R.string.fit_status_bar_title, R.string.fit_status_bar_summary,
               Prefs.K_FIT_STATUS_BAR, Prefs.D_FIT_STATUS_BAR, true);

        section(R.string.section_keyboard);
        toggle(R.string.shortcuts_title, 0, Prefs.K_SHORTCUTS, Prefs.D_SHORTCUTS, false);
        note(getString(R.string.shortcuts_list));
        toggle(R.string.switcher_title, R.string.switcher_summary, Prefs.K_SWITCHER, Prefs.D_SWITCHER, false);
        imeWarningRow();

        section(R.string.section_diag);
        diagnosticsRow();

        section(R.string.section_updates);
        updatesRow();
    }

    // ------------------------------------------------------------------
    // diagnostica
    // ------------------------------------------------------------------
    private static final String ISSUES_URL = "https://github.com/the-marox/HyperDesktopUnlock/issues/new";

    /**
     * Avviso, visibile solo se serve: "Mostra tastiera virtuale" acceso con la tastiera
     * fisica blocca le lettere in alcune app sul monitor. Il valore lo legge
     * system_server (l'app non puo' leggere quell'impostazione nascosta) e, su
     * richiesta, lo spegne.
     */
    private void imeWarningRow() {
        final TextView warn = note(getString(R.string.ime_warning));
        final Button fix = new Button(this);
        fix.setAllCaps(false);
        fix.setText(R.string.ime_warning_button);
        root.addView(fix, matchWidth());
        warn.setVisibility(View.GONE);
        fix.setVisibility(View.GONE);
        fix.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fix.setEnabled(false);
                ModuleClient.send(SettingsActivity.this, "show_ime_off", false, new ModuleClient.Callback() {
                    @Override public void onResult(String data) {
                        if (isFinishing() || isDestroyed()) return;
                        Map<String, String> s = ModuleClient.parse(data);
                        boolean off = s != null && "0".equals(s.get("show_ime"));
                        if (off) { warn.setVisibility(View.GONE); fix.setVisibility(View.GONE); }
                        else fix.setEnabled(true);
                        Toast.makeText(SettingsActivity.this,
                                off ? R.string.ime_warning_done : R.string.ime_warning_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        ModuleClient.send(this, "status", false, new ModuleClient.Callback() {
            @Override public void onResult(String data) {
                if (isFinishing() || isDestroyed()) return;
                Map<String, String> s = ModuleClient.parse(data);
                int vis = s != null && "1".equals(s.get("show_ime")) ? View.VISIBLE : View.GONE;
                warn.setVisibility(vis);
                fix.setVisibility(vis);
            }
        });
    }

    private void diagnosticsRow() {
        final TextView status = note(getString(R.string.diag_checking));
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(R.string.diag_report_button);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { createReport(); }
        });
        root.addView(b, matchWidth());
        small(getString(R.string.diag_note));
        ModuleClient.send(this, "status", true, new ModuleClient.Callback() {
            @Override public void onResult(String data) {
                if (isFinishing() || isDestroyed()) return;
                status.setText(statusLine(data));
            }
        });
    }

    /** Una riga per l'utente: tutto a posto, riavvio necessario, o cosa manca. */
    private String statusLine(String data) {
        Map<String, String> s = ModuleClient.parse(data);
        if (s == null) return getString(R.string.diag_no_answer);
        String loaded = s.get("version");
        if (loaded != null && !loaded.equals(BuildConfig.VERSION_NAME)) {
            return getString(R.string.diag_reboot, loaded, BuildConfig.VERSION_NAME);
        }
        TreeSet<String> missing = new TreeSet<>();
        for (String line : data.split("\n")) {
            if (!line.startsWith("[diag]")) continue;
            int colon = line.indexOf(": ");
            String body = colon > 0 ? line.substring(colon + 2) : line;
            int bar = body.indexOf(" | ");
            if (bar >= 0) body = body.substring(0, bar);
            for (String part : body.split(", ")) {
                if (part.endsWith(" MISSING")) missing.add(part.substring(0, part.length() - 8).trim());
            }
        }
        if (!missing.isEmpty()) return getString(R.string.diag_missing, android.text.TextUtils.join(", ", missing));
        return getString(R.string.diag_ok, loaded);
    }

    private void createReport() {
        final Toast wait = Toast.makeText(this, R.string.diag_collecting, Toast.LENGTH_SHORT);
        wait.show();
        // "report" = status + diagnostica completa, compresa la disposizione del monitor
        ModuleClient.send(this, "report", true, new ModuleClient.Callback() {
            @Override public void onResult(String data) {
                if (isFinishing() || isDestroyed()) return;
                showReport(buildReport(data));
            }
        });
    }

    private String buildReport(String data) {
        StringBuilder r = new StringBuilder();
        r.append("HyperDesktop Unlock ").append(BuildConfig.VERSION_NAME)
         .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        r.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
         .append(" (").append(Build.DEVICE).append(")\n");
        r.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
         .append("), build ").append(Build.VERSION.INCREMENTAL).append('\n');
        String os = getprop("ro.mi.os.version.incremental");
        if (os.isEmpty()) os = getprop("ro.mi.os.version.name");
        r.append("HyperOS: ").append(os.isEmpty() ? "?" : os).append('\n');
        r.append("LSPosed: ").append(Boolean.TRUE.equals(sModuleActive) ? "module active" : "module NOT active").append('\n');
        r.append("Settings:");
        if (prefs != null) {
            for (Map.Entry<String, ?> e : new TreeMap<>(prefs.getAll()).entrySet()) {
                if (Prefs.K_MODES_CACHE.equals(e.getKey())) continue;
                r.append(' ').append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
        }
        r.append("\n\n");
        if (data == null) r.append("No answer from the system: the module is not loaded (reboot after installing or updating it).\n");
        else r.append(data);
        return r.toString();
    }

    private void showReport(final String report) {
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextIsSelectable(true);
        int pad = dp(16);
        tv.setPadding(pad, dp(8), pad, dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle(R.string.diag_report_title)
            .setView(sv)
            .setPositiveButton(R.string.diag_copy, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    copy(report);
                    Toast.makeText(SettingsActivity.this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton(R.string.diag_issue, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    copy(report);
                    Toast.makeText(SettingsActivity.this, R.string.diag_copied_issue, Toast.LENGTH_LONG).show();
                    String body = "**What happened**\n\n\n**Diagnostic report** (copied by the app: paste it between the lines)\n\n```\n\n```\n";
                    String url = ISSUES_URL + "?body=" + Uri.encode(body);
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable ignored) {}
                }
            })
            .setNegativeButton(R.string.diag_share, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "HyperDesktop Unlock report")
                            .putExtra(Intent.EXTRA_TEXT, report);
                    try { startActivity(Intent.createChooser(send, getString(R.string.diag_share))); } catch (Throwable ignored) {}
                }
            })
            .show();
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("HyperDesktop report", text));
        } catch (Throwable ignored) {}
    }

    /** Proprieta' di sistema lette con getprop (consentito alle app per quelle pubbliche). */
    private static String getprop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "getprop", key });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Throwable t) { return ""; }
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
    // normalizzatori dei campi del monitor
    // ------------------------------------------------------------------
    private static final int RES_W_MIN = 320, RES_W_MAX = 7680, RES_H_MIN = 240, RES_H_MAX = 4320;

    private static final Normalizer NORM_DENSITY = new Normalizer() {
        @Override public String normalize(String s) {
            if (s.isEmpty()) return "";
            int v; try { v = Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
            if (v == 0) return "0";
            return (v >= Prefs.DENSITY_MIN && v <= Prefs.DENSITY_MAX) ? String.valueOf(v) : null;
        }
    };

    private static final Normalizer NORM_REFRESH = new Normalizer() {
        @Override public String normalize(String s) {
            if (s.isEmpty()) return "";
            int v; try { v = Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
            if (v == 0) return "0";
            return (v >= 24 && v <= 240) ? String.valueOf(v) : null;
        }
    };

    private static final Normalizer NORM_RESOLUTION = new Normalizer() {
        @Override public String normalize(String s) {
            if (s.isEmpty()) return "";
            if (s.equals("0")) return "0";
            int x = s.indexOf('x'); if (x < 0) x = s.indexOf('X');
            if (x <= 0 || x >= s.length() - 1) return null;
            try {
                int w = Integer.parseInt(s.substring(0, x).trim());
                int h = Integer.parseInt(s.substring(x + 1).trim());
                if (w < RES_W_MIN || w > RES_W_MAX || h < RES_H_MIN || h > RES_H_MAX) return null;
                return w + "x" + h;
            } catch (NumberFormatException e) { return null; }
        }
    };

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

    private void textField(int title, int summary, final String key, String def, String hint) {
        label(title);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(prefs.getString(key, def));
        if (hint != null) e.setHint(hint);
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
     * Campo con pulsante "Applica": il valore e' salvato solo dopo la validazione
     * (un "3" a meta' digitazione non deve mai arrivare al monitor). Vuoto + Applica
     * = il modulo non tocca piu' quel parametro.
     */
    private void applyField(int titleRes, int summaryRes, final int invalidRes, final String key,
                            int inputType, final Normalizer norm) {
        label(titleRes);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (prefs.contains(key)) e.setText(prefs.getString(key, ""));
        row.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button apply = new Button(this);
        apply.setText(R.string.field_apply);
        row.addView(apply, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, matchWidth());
        small(getString(summaryRes));

        final View.OnClickListener onApply = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String raw = e.getText().toString().trim();
                hideKeyboard(e);
                String out = norm.normalize(raw);
                if (out == null) { e.setError(getString(invalidRes)); return; }
                if (out.isEmpty()) {
                    prefs.edit().remove(key).commit();
                    e.setText("");
                    Toast.makeText(SettingsActivity.this, R.string.field_cleared, Toast.LENGTH_LONG).show();
                    return;
                }
                e.setText(out);
                e.setSelection(e.length());
                prefs.edit().putString(key, out).commit();
                applyNow();
                Toast.makeText(SettingsActivity.this, R.string.field_saved, Toast.LENGTH_LONG).show();
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

    // ------------------------------------------------------------------
    // controllo aggiornamenti (rete dall'app, in background, silenzioso in caso di errore)
    // ------------------------------------------------------------------
    private void updatesRow() {
        final TextView v = new TextView(this);
        v.setText(getString(R.string.updates_checking));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setPadding(0, dp(10), 0, dp(10));
        root.addView(v, matchWidth());
        checkUpdates(v);
    }

    private void checkUpdates(final TextView v) {
        final String current = BuildConfig.VERSION_NAME;
        new Thread(new Runnable() {
            @Override public void run() {
                String tag = fetchLatestTag();
                final String latest = tag;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;   // schermata chiusa durante la richiesta
                        if (latest == null) {
                            v.setText(getString(R.string.updates_error));
                            v.setOnClickListener(openReleases());
                        } else if (compareVersions(latest, current) > 0) {
                            v.setText(getString(R.string.updates_available, latest));
                            v.setOnClickListener(openReleases());
                        } else {
                            v.setText(getString(R.string.updates_latest, current));
                            v.setClickable(false);
                        }
                    }
                });
            }
        }, "HyperDesktop-updatecheck").start();
    }

    private View.OnClickListener openReleases() {
        return new View.OnClickListener() {
            @Override public void onClick(View view) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))); }
                catch (Throwable ignored) {}
            }
        };
    }

    private static String fetchLatestTag() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "HyperDesktopUnlock");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String tag = new JSONObject(sb.toString()).optString("tag_name", "");
            return tag.isEmpty() ? null : tag;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Confronto versioni "1.7.0" / "v1.7.0". >0 se a e' piu' recente di b. */
    private static int compareVersions(String a, String b) {
        int[] x = parseVersion(a), y = parseVersion(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0, yi = i < y.length ? y[i] : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int[] parseVersion(String s) {
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int n = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char ch = parts[i].charAt(j);
                if (ch < '0' || ch > '9') break;
                n = n * 10 + (ch - '0');
            }
            out[i] = n;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // scelta tra le modalita' del monitor
    // ------------------------------------------------------------------
    /** Menu a scelta singola: salva uno dei valori quando l'utente ne tocca uno. */
    private void choiceField(int titleRes, int summaryRes, final String key, String[] labels, String[] values) {
        label(titleRes);

        final List<String> L = new ArrayList<>(Arrays.asList(labels));
        final List<String> V = new ArrayList<>(Arrays.asList(values));
        String saved = prefs.contains(key) ? prefs.getString(key, "") : "";
        int idx = V.indexOf(saved);
        if (idx < 0) {   // valore salvato non in elenco (es. digitato in una versione precedente)
            L.add(saved); V.add(saved); idx = V.size() - 1;
        }
        final int[] current = { idx };

        final Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setText(L.get(current[0]));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle(titleRes)
                    .setSingleChoiceItems(L.toArray(new String[0]), current[0],
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                current[0] = which;
                                String val = V.get(which);
                                if (val.isEmpty()) prefs.edit().remove(key).commit();
                                else prefs.edit().putString(key, val).commit();
                                applyNow();
                                b.setText(L.get(which));
                                d.dismiss();
                            }
                        })
                    .show();
            }
        });
        root.addView(b, matchWidth());
        small(getString(summaryRes));
    }

    /**
     * [labels, values]; value "" = non cambiare, "0" = nativa, altrimenti "WxH".
     *
     * La risoluzione e' una scala LOGICA: si offrono solo riduzioni che mantengono
     * le PROPORZIONI della nativa (ridimensionata per fattore). NON si usano le
     * modalita' fisiche del monitor: comprendono formati diversi (anche verticali,
     * es. 1920x2160) che, forzati come display size, ruotano o deformano il monitor.
     */
    private String[][] resolutionOptions(Modes m) {
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        labels.add(getString(R.string.choose_leave));  values.add("");
        labels.add(getString(R.string.choose_native) + " (" + m.nativeW + "×" + m.nativeH + ")"); values.add("0");
        TreeSet<String> seen = new TreeSet<>();
        for (double f : new double[] { 0.75, 2.0 / 3, 0.5, 1.0 / 3 }) {
            int w = even(m.nativeW * f), h = even(m.nativeH * f);
            if (w < 640 || h < 480) continue;                       // niente scale troppo piccole
            if (w == m.nativeW && h == m.nativeH) continue;
            String key = w + "x" + h;
            if (seen.add(key)) { labels.add(w + "×" + h); values.add(key); }
        }
        return new String[][] { labels.toArray(new String[0]), values.toArray(new String[0]) };
    }

    private String[][] refreshOptions(Modes m) {
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        labels.add(getString(R.string.choose_leave));  values.add("");
        labels.add(getString(R.string.choose_native)); values.add("0");
        for (int hz : m.fps) { labels.add(hz + " Hz"); values.add(String.valueOf(hz)); }
        return new String[][] { labels.toArray(new String[0]), values.toArray(new String[0]) };
    }

    private static int even(double v) { int e = (int) Math.round(v); return e - (e % 2); }

    /** Modalita' del monitor: nativa + risoluzioni distinte + frequenze alla risoluzione nativa. */
    private static final class Modes {
        int nativeW, nativeH;
        final List<int[]> res = new ArrayList<>();   // [w,h], decrescente per area
        final List<Integer> fps = new ArrayList<>(); // crescente
    }

    /** Dal monitor collegato se c'e' (e lo memorizza), altrimenti dall'ultima volta; null se mai visto. */
    private Modes loadExternalModes() {
        List<int[]> all = liveModes();
        if (all != null && !all.isEmpty()) { saveModesCache(all); return fromModeList(all); }
        all = parseModesCache(prefs.getString(Prefs.K_MODES_CACHE, ""));
        return (all != null && !all.isEmpty()) ? fromModeList(all) : null;
    }

    private List<int[]> liveModes() {
        try {
            DisplayManager dm = getSystemService(DisplayManager.class);
            if (dm == null) return null;
            Display best = null; long bestArea = -1;
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                Display.Mode cur = d.getMode();
                long area = (long) cur.getPhysicalWidth() * cur.getPhysicalHeight();
                if (area > bestArea) { bestArea = area; best = d; }
            }
            if (best == null) return null;
            List<int[]> out = new ArrayList<>();
            for (Display.Mode mo : best.getSupportedModes()) {
                out.add(new int[] { mo.getPhysicalWidth(), mo.getPhysicalHeight(), Math.round(mo.getRefreshRate()) });
            }
            return out;
        } catch (Throwable t) { return null; }
    }

    private static Modes fromModeList(List<int[]> all) {
        Modes m = new Modes();
        int[] nat = null; long area = -1;
        for (int[] mo : all) { long a = (long) mo[0] * mo[1]; if (a > area) { area = a; nat = mo; } }
        if (nat == null) return null;
        m.nativeW = nat[0]; m.nativeH = nat[1];
        for (int[] mo : all) {
            boolean dup = false;
            for (int[] r : m.res) if (r[0] == mo[0] && r[1] == mo[1]) { dup = true; break; }
            if (!dup) m.res.add(new int[] { mo[0], mo[1] });
        }
        m.res.sort((a, b) -> Long.compare((long) b[0] * b[1], (long) a[0] * a[1]));
        TreeSet<Integer> fps = new TreeSet<>();
        for (int[] mo : all) if (mo[0] == m.nativeW && mo[1] == m.nativeH && mo[2] > 0) fps.add(mo[2]);
        m.fps.addAll(fps);
        return m;
    }

    private void saveModesCache(List<int[]> all) {
        StringBuilder sb = new StringBuilder();
        for (int[] mo : all) sb.append(mo[0]).append('x').append(mo[1]).append('@').append(mo[2]).append(',');
        prefs.edit().putString(Prefs.K_MODES_CACHE, sb.toString()).apply();
    }

    private static List<int[]> parseModesCache(String s) {
        if (s == null || s.isEmpty()) return null;
        List<int[]> out = new ArrayList<>();
        for (String tok : s.split(",")) {
            if (tok.isEmpty()) continue;
            try {
                int x = tok.indexOf('x'), at = tok.indexOf('@');
                if (x <= 0 || at <= x) continue;
                int w = Integer.parseInt(tok.substring(0, x));
                int h = Integer.parseInt(tok.substring(x + 1, at));
                int f = Integer.parseInt(tok.substring(at + 1));
                out.add(new int[] { w, h, f });
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // ------------------------------------------------------------------
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

    /**
     * Cursore min..max salvato come stringa. Il valore si vede mentre lo trascini;
     * viene salvato e mandato a system_server quando lo rilasci.
     */
    private void sliderField(final int titleRes, int summaryRes, final String key,
                             final int min, final int max, final int def) {
        int saved;
        try { saved = Integer.parseInt(prefs.getString(key, String.valueOf(def)).trim()); }
        catch (Throwable t) { saved = def; }
        saved = Math.max(min, Math.min(max, saved));

        final TextView title = new TextView(this);
        title.setText(getString(titleRes, saved, max));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setPadding(0, dp(12), 0, 0);
        root.addView(title);

        final SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(saved);
        bar.setPadding(dp(8), dp(10), dp(8), dp(10));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                title.setText(getString(titleRes, v, max));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putString(key, String.valueOf(s.getProgress())).commit();   // prima del broadcast
                applyNow();
            }
        });
        root.addView(bar, matchWidth());
        small(getString(summaryRes));
    }

    private void label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(12), 0, 0);
        root.addView(t);
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, dp(4));
        t.setAlpha(0.85f);
        root.addView(t);
        return t;
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
