package com.netoop.g9overlay;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Tile do painel: grava no log o estado da GPU AGORA (use no momento em que o bug aparecer). */
public class GpuRecordTile extends TileService {

    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    @Override
    public void onStartListening() {
        paint(false);
    }

    @Override
    public void onClick() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                go();
            }
        };
        if (isLocked()) unlockAndRun(r);
        else r.run();
    }

    private void go() {
        if (busy) return;
        busy = true;
        paint(true);
        GpuTools.record(this, "tile do painel", new GpuTools.Callback() {
            @Override
            public void onResult(final String summary) {
                busy = false;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(GpuRecordTile.this, summary, Toast.LENGTH_LONG).show();
                        paint(false);
                    }
                });
            }
        });
    }

    private void paint(boolean working) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Gravar GPU");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_gpu));
        t.setState(working ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(working ? "Gravando..." : "Registrar no log");
        t.updateTile();
    }
}
