/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * Lato app dei comandi verso i processi agganciati (ModuleControl): broadcast
 * ordinati e MIRATI (setPackage), prima a system_server ("android") e, per il
 * report, poi a SystemUI con il risultato del primo come dato iniziale. Mirati,
 * cosi' nessun'altra app puo' leggerli, bloccarli o alterarne la risposta.
 * In primo piano (FLAG_RECEIVER_FOREGROUND): risposta rapida anche sotto carico.
 * Gira nel processo dell'app (schermata impostazioni e riquadri), mai negli hook.
 */
final class ModuleClient {

    interface Callback { void onResult(String data); }   // null = nessuna risposta

    private static final long TIMEOUT_MS = 4000;

    static void send(final Context ctx, String cmd, final boolean diag, final Callback cb) {
        hop(ctx, "android", cmd, diag, null, cb == null ? null : new Callback() {
            @Override public void onResult(String data) {
                if (!diag) { cb.onResult(data); return; }
                // SystemUI aggiunge solo la sua parte del report: nessun comando
                hop(ctx, "com.android.systemui", "status", true, data, cb);
            }
        });
    }

    private static void hop(Context ctx, String pkg, String cmd, boolean diag, String initial, final Callback cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = { false };
        Intent i = new Intent(Prefs.ACTION_COMMAND)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(Prefs.EXTRA_CMD, cmd)
                .putExtra(Prefs.EXTRA_DIAG, diag);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                if (done[0]) return;
                done[0] = true;
                if (cb != null) cb.onResult(getResultData());
            }
        };
        try {
            ctx.sendOrderedBroadcast(i, null, r, h, Activity.RESULT_OK, initial, null);
        } catch (Throwable t) {
            done[0] = true;
            if (cb != null) cb.onResult(initial);
            return;
        }
        if (cb != null) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    cb.onResult(initial);   // nessuna risposta: resta quello che c'era
                }
            }, TIMEOUT_MS);
        }
    }

    /** Righe "chiave=valore" in testa alla risposta di system_server; null se non ha risposto. */
    static Map<String, String> parse(String data) {
        if (data == null || !data.startsWith("version=")) return null;
        Map<String, String> m = new HashMap<>();
        for (String line : data.split("\n")) {
            if (line.isEmpty()) break;
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    /** Preferenze del modulo leggibili dagli hook; null se LSPosed non attiva il modulo. */
    @SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    static SharedPreferences prefs(Context ctx) {
        try { return ctx.getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE); }
        catch (Throwable t) { return null; }
    }

    private ModuleClient() {}
}
