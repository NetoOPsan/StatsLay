package com.netoop.g9overlay;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tile do painel de notificações: liga/desliga o overlay. */
public class OverlayTile extends TileService {

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
        AppLog.init(this);
        if (OverlayService.RUNNING) {
            AppLog.log("tile", "overlay: parar");
            stopService(new Intent(this, OverlayService.class));
        } else if (!Settings.canDrawOverlays(this)) {
            AppLog.log("tile", "overlay: sem permissão; abrindo o app");
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
        } else {
            AppLog.log("tile", "overlay: iniciar");
            startForegroundService(new Intent(this, OverlayService.class));
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 700);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = OverlayService.RUNNING;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Overlay");
        t.setSubtitle(on ? "Ativo" : "Parado");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_overlay));
        t.updateTile();
    }
}
