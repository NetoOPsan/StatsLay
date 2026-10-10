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
}
