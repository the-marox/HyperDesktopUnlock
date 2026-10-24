/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostica all'avvio: ogni hook segnala se si e' agganciato; a fine
 * caricamento di ogni processo viene stampata UNA riga di riepilogo, es.
 *   [diag] system_server v1.5.0: gate OK, mirror-guard OK, ... | settings: saved
 * Quando un aggiornamento di HyperOS rompe qualcosa, quella riga dice subito cosa.
 * (In inglese: e' la riga da incollare nelle segnalazioni su GitHub.)
 */
final class Diag {

    private static final Map<String, Boolean> marks = new LinkedHashMap<>();
    /** Ultima riga [diag] di questo processo (per il report dell'app). */
    static volatile String last = "";

    static synchronized void mark(String name, boolean ok) {
        Boolean prev = marks.get(name);
        marks.put(name, ok || (prev != null && prev));
    }

    static synchronized void summary(String process) {
        StringBuilder sb = new StringBuilder("[diag] ").append(process)
                .append(" v").append(BuildConfig.VERSION_NAME).append(": ");
        boolean first = true, allOk = true;
        for (Map.Entry<String, Boolean> e : marks.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(e.getValue() ? " OK" : " MISSING");
            allOk &= e.getValue();
            first = false;
        }
        if (marks.isEmpty()) sb.append("no hooks");
        String prefs = Prefs.describeIfLoaded();
        if (prefs != null) sb.append(" | ").append(prefs);
        if (!allOk) sb.append("  <-- some hooks did not attach (firmware changed?)");
        last = sb.toString();
        XLog.i(last);
        marks.clear();
    }

    private Diag() {}
}
