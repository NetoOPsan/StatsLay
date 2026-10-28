package com.netoop.g9overlay;

/**
 * Decide QUANDO tentar soltar a GPU sozinho. Regras de segurança:
 *  - só age quando a saúde pede conserto (limitada ou presa no mínimo);
 *  - NÃO age se a GPU estiver quente (>= cutoff): aí a proteção do kernel precisa valer;
 *  - no máximo 1 tentativa a cada 30 s e 3 tentativas por episódio; depois pausa 10 min (evita brigar com o kernel).
 * Sem dependência do Android.
 */
public final class GpuGuard {

    public static final long COOLDOWN_MS = 30000;
    public static final int MAX_ATTEMPTS = 3;
    public static final long PAUSE_MS = 10 * 60 * 1000;

    public enum Decision { NOTHING, ACT, HOT_PAUSE, NO_TEMP, GAVE_UP }

    private long lastActMs = -COOLDOWN_MS, pausedUntil = 0, lastHotMs = 0, healthySince = 0;
    private int attempts = 0;

    public synchronized Decision decide(GpuHealth.Result r, int gpuTemp, long now, int cutoff) {
        if (r.state == GpuHealth.State.OK || r.state == GpuHealth.State.PINNED) {
            if (healthySince == 0) healthySince = now;
            if (now - healthySince >= 10000) attempts = 0; // episódio resolvido
            return Decision.NOTHING;
        }
        healthySince = 0;
        if (!r.needsFix()) return Decision.NOTHING;
        if (now < pausedUntil) return Decision.NOTHING;
        if (gpuTemp <= 0) {                       // sem leitura de temperatura: NÃO age (segurança)
            if (now - lastHotMs >= 60000) {
                lastHotMs = now;
                return Decision.NO_TEMP;
            }
            return Decision.NOTHING;
        }
        if (gpuTemp >= cutoff) {
            if (now - lastHotMs >= 60000) {
                lastHotMs = now;
                return Decision.HOT_PAUSE;
            }
            return Decision.NOTHING;
        }
        if (now - lastActMs < COOLDOWN_MS) return Decision.NOTHING;
        if (attempts >= MAX_ATTEMPTS) {
            pausedUntil = now + PAUSE_MS;
            attempts = 0;
            return Decision.GAVE_UP;
        }
        attempts++;
        lastActMs = now;
        return Decision.ACT;
    }
}
