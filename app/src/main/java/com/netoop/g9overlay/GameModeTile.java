package com.netoop.g9overlay;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Tile "Modo Jogo" do painel de notificações.
 * Liga/desliga o módulo gamemode-g9play (mesmo efeito do botão Ação do KernelSU).
 *  - Ativo   = estado do módulo é "auto" ou "manual"
 *  - Inativo = estado "off"
 *  - Indisponível = módulo não instalado
 * Tudo que usa root roda em thread separada, para não travar o painel.
 */
public class GameModeTile extends TileService {

    private static final String SCRIPT = "/data/adb/modules/gamemode-g9play/gamemode.sh";
    private static final String STATE  = "/data/adb/gamemode-g9play/state";

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Painel foi aberto: lê o estado real do módulo. */
    @Override
    public void onStartListening() {
        refreshAsync();
    }

    /** Toque no tile: alterna o Modo Jogo. */
    @Override
    public void onClick() {
        // Feedback imediato enquanto o módulo trabalha (ligar leva alguns segundos)
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_UNAVAILABLE);
            t.setLabel("Modo Jogo…");
            t.updateTile();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // "nohup ... &" deixa o script rodando mesmo se o painel fechar
                su("[ -f " + SCRIPT + " ] && nohup sh " + SCRIPT + " toggle >/dev/null 2>&1 &");
                // ligar grava o estado na hora; desligar só no fim: confere algumas vezes
                for (int i = 0; i < 6; i++) {
                    try { Thread.sleep(i == 0 ? 1500 : 2500); } catch (InterruptedException ignored) {}
                    refreshNow();
                }
            }
        }).start();
    }

    private void refreshAsync() {
        new Thread(new Runnable() {
            @Override public void run() { refreshNow(); }
        }).start();
    }

    /** Lê o estado (com root) e atualiza o tile na thread principal. */
    private void refreshNow() {
        final String out = su("if [ -f " + SCRIPT + " ]; then cat " + STATE + " 2>/dev/null || echo off; else echo NOMOD; fi");
        ui.post(new Runnable() {
            @Override public void run() {
                Tile t = getQsTile();
                if (t == null) return;
                t.setLabel("Modo Jogo");
                if (out.startsWith("NOMOD") || out.isEmpty()) {
                    t.setState(Tile.STATE_UNAVAILABLE);
                    t.setLabel(out.isEmpty() ? "Modo Jogo (sem root)" : "Sem módulo");
                } else if (out.startsWith("auto") || out.startsWith("manual")) {
                    t.setState(Tile.STATE_ACTIVE);
                } else {
                    t.setState(Tile.STATE_INACTIVE);
                }
                t.updateTile();
            }
        });
    }

    /** Roda um comando como root e devolve a primeira linha da saída ("" se falhar). */
    private static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
