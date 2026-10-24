/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.Map;

/**
 * Riquadro "Monitor" nelle impostazioni rapide: Computer <-> Mirroring senza
 * staccare il cavo. Lo stato e il cambio passano da system_server (ModuleControl).
 * Attivo = Computer (desktop), spento = Mirroring. Senza risposta dal sistema o
 * senza monitor non fa nulla (aggiorna solo lo stato).
 */
public class MonitorTile extends TileService {

    private Map<String, String> last;

    @Override public void onStartListening() {
        ModuleClient.send(this, "status", false, this::update);
    }

    @Override public void onClick() {
        // dalla schermata di blocco: prima si sblocca
        if (isLocked()) { unlockAndRun(new Runnable() { @Override public void run() { toggle(); } }); return; }
        toggle();
    }

    private void toggle() {
        Map<String, String> s = last;
        Tile t = getQsTile();
        if (s == null || t == null) { onStartListening(); return; }
        boolean mirror = "1".equals(s.get("mirror"));
        boolean monitor = !"-1".equals(s.get("monitor"));
        if (!mirror && !monitor) { onStartListening(); return; }   // nessun monitor
        show(t, mirror);   // subito, poi la conferma da system_server
        ModuleClient.send(this, mirror ? "desktop" : "mirror", false, this::update);
    }

    private void update(String data) {
        Tile t = getQsTile();
        if (t == null) return;
        Map<String, String> s = ModuleClient.parse(data);
        last = s;
        t.setLabel(getString(R.string.tile_monitor));
        if (s == null) {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle(getString(R.string.tile_not_active));
            t.updateTile();
            return;
        }
        boolean mirror = "1".equals(s.get("mirror"));
        if (!mirror && "-1".equals(s.get("monitor"))) {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle(getString(R.string.tile_no_monitor));
            t.updateTile();
            return;
        }
        show(t, !mirror);
    }

    private void show(Tile t, boolean desktop) {
        t.setLabel(getString(R.string.tile_monitor));
        t.setState(desktop ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(desktop ? R.string.tile_computer : R.string.tile_mirroring));
        t.updateTile();
    }
}
