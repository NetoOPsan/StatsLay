package com.netoop.g9overlay;

/**
 * Diz se a GPU está "normal" ou com problema, a partir das amostras da caixa-preta.
 * Precisa de algumas leituras seguidas para mudar de estado (evita piscar). Sem dependência do Android.
 */
public final class GpuHealth {

    public enum State { NO_DATA, OK, PINNED, SLOW_GOV, LIMITED, STUCK }

    public static final class Result {
        public final State state;
        public final String text;   // ex.: "normal", "LIMITADA: teto 465/950 MHz"
        public final int level;     // 0 ok, 1 atenção, 2 problema, 3 sem dado

        Result(State state, String text, int level) {
            this.state = state;
            this.text = text;
            this.level = level;
        }

        /** Há algo que o "guarda" deveria tentar soltar? */
        public boolean needsFix() {
            return state == State.LIMITED || state == State.STUCK;
        }
    }

    private int stuckRun = 0, limitRun = 0, slowRun = 0;
    private Result last = new Result(State.NO_DATA, "sem dados", 3);

    public Result last() {
        return last;
    }

    public synchronized Result update(GpuBlackBox.Sample s) {
        if (s == null) {
            stuckRun = limitRun = slowRun = 0;
            last = new Result(State.NO_DATA, "sem dados", 3);
            return last;
        }
        boolean pinned = s.min > 0 && s.hwMax > 0 && s.min >= s.hwMax;
        stuckRun = (s.atMin() && (s.busy >= 60 || s.limited())) ? stuckRun + 1 : 0;
        limitRun = s.limited() ? limitRun + 1 : 0;
        // "governor segura o clock": carga alta, sem limite, e o clock longe do máximo
        boolean slow = !s.limited() && !pinned && s.hwMax > 0 && s.cur > 0 && s.cur < s.hwMax * 0.8 && s.busy >= 85;
        slowRun = slow ? slowRun + 1 : 0;

        if (stuckRun >= 3) {
            last = new Result(State.STUCK, "PRESA no mínimo (" + s.cur + " MHz, carga " + s.busy + "%)", 2);
        } else if (limitRun >= 2) {
            StringBuilder why = new StringBuilder();
            if (s.thermal > 0) why.append("térmico ").append(s.thermal).append(' ');
            if (s.maxLvl > 0) why.append("nível ").append(s.maxLvl).append(' ');
            if (s.max > 0 && s.hwMax > 0 && s.max < s.hwMax) why.append("teto ").append(s.max).append('/').append(s.hwMax).append(" MHz");
            last = new Result(State.LIMITED, "LIMITADA: " + why.toString().trim(), 2);
        } else if (slowRun >= 4) {
            last = new Result(State.SLOW_GOV, "governor segura o clock (" + s.cur + "/" + s.hwMax + " MHz, carga " + s.busy + "%)", 1);
        } else if (pinned) {
            last = new Result(State.PINNED, "fixa em " + s.min + " MHz", 0);
        } else {
            last = new Result(State.OK, "normal", 0);
        }
        return last;
    }
}
