/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.os.SystemClock;

/**
 * Interruttore generale del modulo (idea dal fork di oldbkroll).
 *
 * Spento: ogni hook lascia passare il comportamento originale di HyperOS, senza
 * riavvio. Gli hook restano installati: cambia solo cosa fanno.
 *
 * Il valore viene dalle impostazioni dell'app (Prefs.K_ENABLED, predefinito acceso)
 * ed e' tenuto in un campo volatile: il file e' ricontrollato al massimo una volta
 * al secondo, quindi la lettura costa pochissimo anche nei gate chiamati spesso.
 * Se le impostazioni non sono leggibili il modulo resta acceso (come sempre).
 */
final class ModuleState {

    private static final long RECHECK_MS = 1000;
    private static volatile boolean enabled = true;
    private static volatile long nextCheck;

    static boolean enabled() {
        long now = SystemClock.uptimeMillis();
        if (now >= nextCheck) {
            nextCheck = now + RECHECK_MS;
            try { enabled = Prefs.bool(Prefs.K_ENABLED, Prefs.D_ENABLED); }
            catch (Throwable ignored) {}
        }
        return enabled;
    }

    /** Rilettura immediata (comando dell'app). */
    static boolean refresh() {
        nextCheck = 0;
        return enabled();
    }

    private ModuleState() {}
}
