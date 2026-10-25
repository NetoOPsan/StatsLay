package com.netoop.g9overlay;

/**
 * Comandos de shell (root) usados pelos tiles do painel e a interpretação das respostas.
 * Sem dependência do Android, para poder testar no PC.
 */
public final class TileScripts {

    public static final String PERF_DIR = "/data/adb/modules/perf-gpu-cpu-g9play";
    public static final String ZRAM_DIR = "/data/adb/modules/zram-4gb-lz4";

    private final String perf, zram;

    public TileScripts() {
        this(PERF_DIR, ZRAM_DIR);
    }

    public TileScripts(String perfDir, String zramDir) {
        this.perf = perfDir;
        this.zram = zramDir;
    }

    /** Responde NOMOD (módulo ausente), OFF (tem o arquivo "disable") ou ON. */
    public String perfState() {
        return "P=" + perf + "; if [ -d \"$P\" ]; then if [ -f \"$P/disable\" ]; then echo OFF; else echo ON; fi; "
                + "else echo NOMOD; fi";
    }

    /**
     * on=true : remove o "disable" (igual ao interruptor do KernelSU Manager) e religa o vigia do módulo.
     * on=false: cria o "disable" PRIMEIRO (o vigia para de reaplicar e encerra) e devolve CPU/GPU ao padrão já.
     */
    public String perfSet(boolean on) {
        String head = "P=" + perf + "; [ -d \"$P\" ] || { echo NOMOD; exit 0; }; ";
        if (on) {
            return head + "rm -f \"$P/disable\"; "
                    + "if command -v setsid >/dev/null 2>&1; then setsid sh \"$P/watcher.sh\" >/dev/null 2>&1 & "
                    + "else nohup sh \"$P/watcher.sh\" >/dev/null 2>&1 & fi; sleep 1; echo ON";
        }
        return head + "touch \"$P/disable\"; sh \"$P/perf.sh\" off; echo OFF";
    }

    /** Roda o clean.sh do módulo de ZRAM; sem ele, só mata os apps em cache. */
    public String clean() {
        return "Z=" + zram + "; if [ -f \"$Z/clean.sh\" ]; then sh \"$Z/clean.sh\"; "
                + "else am kill-all; echo 'DEPOIS: (modulo de ZRAM ausente; so am kill-all)'; fi";
    }

    /** Lê "ANTES: RAM disponível: N MB" e "DEPOIS: ..." e devolve {antes, depois} em MB (ou null). */
    public static int[] parseClean(String out) {
        int before = -1, after = -1;
        for (String l : out.split("\n")) {
            String t = l.trim();
            int i = t.indexOf("RAM dispon");
            if (i < 0) continue;
            int mb = firstInt(t.substring(i).replaceFirst("^[^:]*:", ""));
            if (t.contains("ANTES")) before = mb;
            else if (t.contains("DEPOIS")) after = mb;
        }
        return (before >= 0 && after >= 0) ? new int[]{before, after} : null;
    }

    private static int firstInt(String s) {
        StringBuilder d = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                d.append(c);
                started = true;
            } else if (started) {
                break;
            }
        }
        try {
            return d.length() == 0 ? -1 : Integer.parseInt(d.toString());
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ GPU

    public static final String KGSL = "/sys/class/kgsl/kgsl-3d0";

    /** Despeja o estado da GPU (arquivos do kgsl, temperaturas e linhas do dmesg) para o log. */
    public static String gpuDiag() {
        return gpuDiag(KGSL);
    }

    public static String gpuDiag(String g) {
        return "G=" + g + "; for f in devfreq/governor devfreq/min_freq devfreq/max_freq devfreq/cur_freq "
                + "devfreq/available_frequencies gpuclk max_gpuclk thermal_pwrlevel min_pwrlevel max_pwrlevel "
                + "default_pwrlevel gpubusy gpu_busy_percentage idle_timer force_clk_on force_bus_on force_rail_on "
                + "force_no_nap; do [ -r \"$G/$f\" ] && echo \"$f = $(cat \"$G/$f\")\"; done; "
                + "echo '--- arquivos do kgsl:'; ls \"$G\"; "
                + "echo '--- temperaturas:'; for z in /sys/class/thermal/thermal_zone*; do t=$(cat \"$z/type\" 2>/dev/null); "
                + "case \"$t\" in *gpu*|msm_therm) echo \"$t $(cat \"$z/temp\" 2>/dev/null)\";; esac; done; "
                + "echo '--- dmesg:'; dmesg 2>/dev/null | grep -iE 'kgsl|adreno|gpu|thermal|devfreq' | tail -40";
    }

    /**
     * Tenta soltar a GPU, UM passo por vez, e imprime o clock depois de cada um. Assim o log mostra
     * qual limite estava preso. Tudo volta ao padrão no reboot.
     */
    public static String gpuUnstick() {
        return gpuUnstick(KGSL);
    }

    public static String gpuUnstick(String g) {
        return "G=" + g + "; "
                + "rd() { echo \"cur=$(cat $G/devfreq/cur_freq) clk=$(cat $G/gpuclk 2>/dev/null) busy=$(cat $G/gpubusy 2>/dev/null) "
                + "lim=$(cat $G/thermal_pwrlevel 2>/dev/null):$(cat $G/max_pwrlevel 2>/dev/null):$(cat $G/devfreq/max_freq)\"; }; "
                + "MAXF=$(tr ' ' '\\n' < $G/devfreq/available_frequencies | sort -n | tail -n 1); "
                + "MINF=$(cat $G/devfreq/min_freq); "
                + "echo \"ANTES $(rd)\"; "
                + "echo 0 > $G/thermal_pwrlevel; sleep 1; echo \"PASSO1 thermal_pwrlevel=0 $(rd)\"; "
                + "echo 0 > $G/max_pwrlevel; sleep 1; echo \"PASSO2 max_pwrlevel=0 $(rd)\"; "
                + "echo $MAXF > $G/devfreq/max_freq; sleep 1; echo \"PASSO3 devfreq/max_freq=$MAXF $(rd)\"; "
                + "echo msm-adreno-tz > $G/devfreq/governor; sleep 1; echo \"PASSO4 governor=msm-adreno-tz $(rd)\"; "
                + "echo $MAXF > $G/devfreq/min_freq; sleep 1; echo \"PASSO5 min_freq=max (pino temporario) $(rd)\"; "
                + "echo $MINF > $G/devfreq/min_freq; sleep 1; echo \"PASSO6 min_freq restaurado=$MINF $(rd)\"; "
                + "echo FIM";
    }

    /** Diz em que passo o clock subiu (ou que nenhum mudou). */
    public static String parseUnstick(String out) {
        long baseCur = -1, baseClk = -1;
        for (String l : out.split("\n")) {
            String t = l.trim();
            boolean antes = t.startsWith("ANTES");
            boolean passo = t.startsWith("PASSO");
            if (!antes && !passo) continue;
            long cur = numAfter(t, "cur="), clk = numAfter(t, "clk=");
            if (antes) {
                baseCur = cur;
                baseClk = clk;
                continue;
            }
            boolean up = (baseCur > 0 && cur > baseCur * 3 / 2) || (baseClk > 0 && clk > baseClk * 3 / 2);
            if (up) {
                int sp = t.indexOf(' ');
                int sp2 = sp < 0 ? -1 : t.indexOf(" cur=");
                return "Clock subiu em " + t.substring(0, sp < 0 ? t.length() : sp)
                        + (sp2 > sp ? " (" + t.substring(sp + 1, sp2) + ")" : "");
            }
        }
        return "Nenhum passo mudou o clock (veja o log)";
    }

    private static long numAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return -1;
        StringBuilder d = new StringBuilder();
        for (int j = i + key.length(); j < s.length() && Character.isDigit(s.charAt(j)); j++) d.append(s.charAt(j));
        try {
            return d.length() == 0 ? -1 : Long.parseLong(d.toString());
        } catch (Exception e) {
            return -1;
        }
    }
}
