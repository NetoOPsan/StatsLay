package com.netoop.g9overlay;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Tile do painel: limpa a memória uma vez (apps em cache + compactação) e mostra quanto ficou livre. */
public class CleanTile extends TileService {

    private final Handler h = new Handler(Looper.getMainLooper());
    private final TileScripts scripts = new TileScripts();
    private volatile boolean busy = false;
    private volatile String lastSub = "Toque para limpar";

    @Override
    public void onStartListening() {
        paint(false);
    }

    @Override
    public void onClick() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                clean();
            }
        };
        if (isLocked()) unlockAndRun(r);
        else r.run();
    }

    private void clean() {
        if (busy) return;
        busy = true;
        paint(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppLog.init(CleanTile.this);
                String out = Root.run(scripts.clean(), 30);
                int[] r = TileScripts.parseClean(out);
                final String msg;
                if (r != null) {
                    lastSub = "Livre " + r[1] + " MB";
                    msg = "Memória livre: " + r[0] + " → " + r[1] + " MB";
                } else {
                    lastSub = out.contains("ERRO") ? "Falhou" : "Feito";
                    msg = out.contains("ERRO") ? "Falhou: " + out : "Limpeza feita";
                }
                AppLog.log("tile", "limpar RAM -> " + out.replace('\n', ' '));
                busy = false;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(CleanTile.this, msg, Toast.LENGTH_SHORT).show();
                        paint(false);
                    }
                });
            }
        }, "g9ov-tile-clean").start();
    }

    private void paint(boolean working) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Limpar RAM");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_clean));
        t.setState(working ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(working ? "Limpando..." : lastSub);
        t.updateTile();
    }
}
