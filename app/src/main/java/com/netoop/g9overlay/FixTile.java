package com.netoop.g9overlay;

import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Tile do painel de notificações (Quick Settings): "Fixar Overlay".
 * Trava o overlay na posição atual e faz o toque atravessá-lo diretamente
 * para os apps/jogos que estão embaixo.
 */
public class FixTile extends TileService {

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                toggle();
            }
        };
        if (isLocked()) unlockAndRun(r);
        else r.run();
    }

    private void toggle() {
        Prefs prefs = new Prefs(this);
        boolean wasFixed = prefs.isFixed();
        boolean newFixed = !wasFixed;
        prefs.setFixed(newFixed);

        AppLog.init(this);
        AppLog.log("tile", "fixar overlay: " + (newFixed ? "FIXADO" : "LIVRE"));

        String msg;
        if (!OverlayService.RUNNING) {
            msg = newFixed
                    ? "Overlay fixado (toque atravessará quando ativo)"
                    : "Overlay liberado para toque";
        } else {
            msg = newFixed
                    ? "Overlay fixado (toque atravessa)"
                    : "Overlay liberado (toque interage)";
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        Prefs prefs = new Prefs(this);
        boolean on = OverlayService.RUNNING;
        boolean fixed = prefs.isFixed();

        t.setLabel("Fixar Overlay");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_fix));

        if (!on) {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle(fixed ? "Parado (Fixado)" : "Parado");
        } else if (fixed) {
            t.setState(Tile.STATE_ACTIVE);
            t.setSubtitle("Fixado · Atravessa");
        } else {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle("Livre para mover");
        }
        t.updateTile();
    }
}
