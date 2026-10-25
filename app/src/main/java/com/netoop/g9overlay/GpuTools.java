package com.netoop.g9overlay;

import android.content.Context;

/** Ações de diagnóstico da GPU usadas pelo painel do overlay, pela tela do app e pelos tiles. */
public final class GpuTools {

    public interface Callback {
        void onResult(String summary);
    }

    private GpuTools() {
    }

    /** Grava no log: as últimas amostras da caixa-preta + o estado atual dos arquivos da GPU + dmesg. */
    public static void record(final Context c, final String why, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppLog.init(app);
                AppLog.log("gpu", "=== GRAVAÇÃO (" + why + ") ===");
                AppLog.log("gpu", "últimas amostras da caixa-preta (mais antigas primeiro):\n" + GpuBlackBox.INSTANCE.dump(60));
                String d = Root.run(TileScripts.gpuDiag(), 25);
                AppLog.log("gpu", "estado atual:\n" + d);
                if (cb != null) cb.onResult("GPU gravada no log");
            }
        }, "g9ov-gpu-record").start();
    }

    /** Tenta soltar a GPU passo a passo e grava o resultado no log. */
    public static void unstick(final Context c, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppLog.init(app);
                String out = Root.run(TileScripts.gpuUnstick(), 40);
                String summary = out.contains("ERRO") ? "Falhou: " + out : TileScripts.parseUnstick(out);
                AppLog.log("gpu", "DESTRAVAR GPU:\n" + out + "\n=> " + summary);
                if (cb != null) cb.onResult(summary);
            }
        }, "g9ov-gpu-unstick").start();
    }
}
