package com.netoop.g9overlay;

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Tile do painel: liga/desliga o módulo "Performance GPU+CPU" (mesmo efeito do interruptor do
 * KernelSU Manager). Ao desligar, CPU e GPU voltam ao padrão na hora; ao ligar, o vigia do módulo é religado.
 */
public class PerfTile extends TileService {

    private final Handler h = new Handler(Looper.getMainLooper());
    private final TileScripts scripts = new TileScripts();
    private volatile String state = "?"; // ON, OFF, NOMOD ou ?
    private volatile boolean busy = false;

    @Override
    public void onStartListening() {
        paint();
        readState();
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

    private void readState() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Root.run(scripts.perfState(), 10);
                state = out.contains("NOMOD") ? "NOMOD" : out.contains("OFF") ? "OFF" : out.contains("ON") ? "ON" : "?";
                post(new Runnable() {
                    @Override
                    public void run() {
                        paint();
                    }
                });
            }
        }, "g9ov-tile-perf").start();
    }

    private void toggle() {
        if (busy) return;
        if ("NOMOD".equals(state)) {
            Toast.makeText(this, "Módulo Performance GPU+CPU não encontrado", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean turnOn = !"ON".equals(state);
        busy = true;
        paintBusy(turnOn ? "Ligando..." : "Desligando...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppLog.init(PerfTile.this);
                String out = Root.run(scripts.perfSet(turnOn), 15);
                AppLog.log("tile", "performance " + (turnOn ? "ON" : "OFF") + " -> " + out.replace('\n', ' '));
                if (out.contains("NOMOD")) state = "NOMOD";
                else if (out.contains("ERRO")) state = "?";
                else state = turnOn ? "ON" : "OFF";
                busy = false;
                final String msg = out.contains("ERRO") ? "Falhou: " + out
                        : turnOn ? "Performance ligada" : "Performance desligada: CPU/GPU no padrão";
                post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PerfTile.this, msg, Toast.LENGTH_SHORT).show();
                        paint();
                    }
                });
            }
        }, "g9ov-tile-perf-set").start();
    }

    private void post(Runnable r) {
        h.post(r);
    }

    private void paintBusy(String sub) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_ACTIVE);
        t.setSubtitle(sub);
        t.updateTile();
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Performance");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_tile_perf));
        if ("ON".equals(state)) {
            t.setState(Tile.STATE_ACTIVE);
            t.setSubtitle("Ligada");
        } else if ("OFF".equals(state)) {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle("Desligada");
        } else if ("NOMOD".equals(state)) {
            t.setState(Tile.STATE_UNAVAILABLE);
            t.setSubtitle("Sem módulo");
        } else {
            t.setState(Tile.STATE_INACTIVE);
            t.setSubtitle("Verificando...");
        }
        t.updateTile();
    }
}
