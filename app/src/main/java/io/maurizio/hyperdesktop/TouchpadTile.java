/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Riquadro "Touchpad" nelle impostazioni rapide. Acceso = opzione "Usa il telefono
 * come touchpad" attiva; toccandolo con il monitor in desktop il telefono diventa
 * subito il touchpad (si chiude anche il pannello). Spento = telefono normale.
 */
public class TouchpadTile extends TileService {

    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        SharedPreferences p = ModuleClient.prefs(this);
        if (p == null) {
            t.setState(Tile.STATE_UNAVAILABLE);
            t.setSubtitle(getString(R.string.tile_not_active));
            t.updateTile();
            return;
        }
        show(t, p.getBoolean(Prefs.K_TOUCHPAD, Prefs.D_TOUCHPAD));
    }

    @Override public void onClick() {
        // dalla schermata di blocco: prima si sblocca
        if (isLocked()) { unlockAndRun(new Runnable() { @Override public void run() { toggle(); } }); return; }
        toggle();
    }

    private void toggle() {
        Tile t = getQsTile();
        SharedPreferences p = ModuleClient.prefs(this);
        if (t == null || p == null) return;
        boolean on = t.getState() != Tile.STATE_ACTIVE;
        p.edit().putBoolean(Prefs.K_TOUCHPAD, on).commit();   // prima del comando
        show(t, on);
        ModuleClient.send(this, on ? "touchpad_on" : "touchpad_off", false, null);
    }

    private void show(Tile t, boolean on) {
        t.setLabel(getString(R.string.tile_touchpad));
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(on ? R.string.tile_on : R.string.tile_off));
        t.updateTile();
    }
}
