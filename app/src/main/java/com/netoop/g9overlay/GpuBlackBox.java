package com.netoop.g9overlay;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * "Caixa-preta" da GPU: guarda as últimas amostras (1 por ciclo) e avisa quando a GPU parece presa.
 * Serve para descobrir POR QUE a GPU fica no mínimo: no momento do bug, o log já tem o que aconteceu
 * nos segundos anteriores. Sem dependência do Android (dá para testar no PC).
 */
public final class GpuBlackBox {

    public static final GpuBlackBox INSTANCE = new GpuBlackBox();

    /** Uma leitura da GPU (clocks em MHz; -1 = não disponível). */
    public static final class Sample {
        public final long t;
        public final int cur, clk, min, max, hwMax, hwMin;   // clock pedido, clock real, piso/teto do devfreq, tabela do hardware
        public final int thermal, minLvl, maxLvl;            // thermal_pwrlevel, min_pwrlevel, max_pwrlevel
        public final int busy;                               // ocupação (%)
        public final String gov;
        public final int gpuTemp, cpuPct, goldPct, fps;

        public Sample(long t, int cur, int clk, int min, int max, int hwMax, int hwMin, int thermal, int minLvl,
                      int maxLvl, int busy, String gov, int gpuTemp, int cpuPct, int goldPct, int fps) {
            this.t = t;
            this.cur = cur;
            this.clk = clk;
            this.min = min;
            this.max = max;
            this.hwMax = hwMax;
            this.hwMin = hwMin;
            this.thermal = thermal;
            this.minLvl = minLvl;
            this.maxLvl = maxLvl;
            this.busy = busy;
            this.gov = gov == null ? "?" : gov;
            this.gpuTemp = gpuTemp;
            this.cpuPct = cpuPct;
            this.goldPct = goldPct;
            this.fps = fps;
        }

        /** Há algum LIMITE ativo (térmico, nível máximo ou teto do devfreq abaixo do hardware)? */
        public boolean limited() {
            return thermal > 0 || maxLvl > 0 || (max > 0 && hwMax > 0 && max < hwMax);
        }

        /** O clock pedido está no menor degrau da tabela? */
        public boolean atMin() {
            return cur > 0 && hwMin > 0 && cur <= hwMin;
        }

        /** O clock real difere muito do pedido enquanto a GPU trabalha? */
        public boolean clockMismatch() {
            return clk > 0 && cur > 0 && busy >= 30 && Math.abs(clk - cur) > cur * 0.25;
        }
    }

    private static final int MAX_SAMPLES = 180;
    private static final long ALERT_COOLDOWN_MS = 90000;

    private final ArrayDeque<Sample> ring = new ArrayDeque<Sample>();
    private int stuckRun = 0, limitRun = 0, mismatchRun = 0;
    private long lastAlertMs = 0;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private GpuBlackBox() {
    }

    /** Guarda a amostra e devolve um texto de alerta (ou null). Um alerta por episódio, com intervalo mínimo. */
    public synchronized String push(Sample s) {
        ring.addLast(s);
        while (ring.size() > MAX_SAMPLES) ring.removeFirst();

        boolean stuck = s.atMin() && (s.busy >= 60 || s.limited());
        stuckRun = stuck ? stuckRun + 1 : 0;
        limitRun = s.limited() ? limitRun + 1 : 0;
        mismatchRun = s.clockMismatch() ? mismatchRun + 1 : 0;

        String why = null;
        if (stuckRun >= 4) {
            why = "GPU PRESA NO MÍNIMO (" + s.cur + " MHz, ocupação " + s.busy + "%" + (s.limited() ? ", com limite ativo" : "") + ")";
        } else if (limitRun >= 3) {
            why = "LIMITE ATIVO NA GPU (thermal_pwrlevel=" + s.thermal + ", max_pwrlevel=" + s.maxLvl
                    + ", max_freq=" + s.max + " de " + s.hwMax + " MHz)";
        } else if (mismatchRun >= 5) {
            why = "CLOCK REAL DIFERENTE DO PEDIDO (pedido " + s.cur + " MHz, real " + s.clk + " MHz, ocupação " + s.busy + "%)";
        }
        if (why == null) return null;
        if (s.t - lastAlertMs < ALERT_COOLDOWN_MS) return null;
        lastAlertMs = s.t;
        return why;
    }

    /** Linha de texto de uma amostra. */
    public String line(Sample s) {
        String time;
        synchronized (fmt) {
            time = fmt.format(new Date(s.t));
        }
        return time + " cur=" + s.cur + " real=" + s.clk + " min=" + s.min + " max=" + s.max
                + " hw=" + s.hwMax + "/" + s.hwMin + " gov=" + s.gov
                + " therm=" + s.thermal + " lvl(min/max)=" + s.minLvl + "/" + s.maxLvl
                + " busy=" + s.busy + "% tGPU=" + s.gpuTemp + " cpu=" + s.cpuPct + "/gold" + s.goldPct + " fps=" + s.fps;
    }

    /** As últimas n amostras (as mais antigas primeiro). */
    public synchronized String dump(int n) {
        if (ring.isEmpty()) return "(sem amostras: ligue a métrica GPU e deixe o overlay rodando)";
        int skip = Math.max(0, ring.size() - n);
        StringBuilder sb = new StringBuilder();
        Iterator<Sample> it = ring.iterator();
        int i = 0;
        while (it.hasNext()) {
            Sample s = it.next();
            if (i++ < skip) continue;
            sb.append(line(s)).append('\n');
        }
        return sb.toString();
    }

    public synchronized void clear() {
        ring.clear();
        stuckRun = limitRun = mismatchRun = 0;
        lastAlertMs = 0;
    }
}
