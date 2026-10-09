package com.netoop.g9overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Monta o comando que o shell root executa a cada ciclo (só o que está ligado) e transforma
 * a saída em linhas de texto para o overlay. Não depende de nada do Android (dá para testar no PC).
 */
public class Collector {

    /** level: 0 normal, 1 atenção, 2 crítico, 3 apagado (informativo). */
    public static class Line {
        public final String text;
        public final int level;

        public Line(String text, int level) {
            this.text = text;
            this.level = level;
        }
    }

    private static final String CPU = "/sys/devices/system/cpu/cpu";
    private static final String GPU = "/sys/class/kgsl/kgsl-3d0/";
    private static final String BAT = "/sys/class/power_supply/battery/";

    private final String selfPkg;
    private Set<String> en;
    private int tick = 0;

    // estado guardado entre ciclos (para calcular variações)
    private final Map<String, long[]> prevCpu = new HashMap<String, long[]>();
    private long prevSwpIn = -1, prevSwpOut = -1, prevVmMs = 0;
    private final Map<Integer, String> zoneTypes = new TreeMap<Integer, String>();
    private final List<Integer> tempZones = new ArrayList<Integer>();
    private int targetPid = 0;
    private String targetName = "";
    private String fpsLayer = "";
    private long prevFrameT = -1;

    public Collector(String selfPkg) {
        this.selfPkg = selfPkg;
    }

    private boolean has(String k) {
        return en.contains(k);
    }

    // ------------------------------------------------------------------ comando

    public String buildCommand(Set<String> enabled, int ownPid) {
        en = enabled;
        tick++;
        StringBuilder c = new StringBuilder();

        if (has("cpu")) c.append("r /proc/stat;");
        if (has("cpufreq")) {
            for (int i = 0; i < 8; i++) c.append("r ").append(CPU).append(i).append("/cpufreq/scaling_cur_freq;");
        }
        if (has("limit")) {
            int[] cl = {0, 4};
            for (int i : cl) {
                c.append("r ").append(CPU).append(i).append("/cpufreq/scaling_max_freq;");
                c.append("r ").append(CPU).append(i).append("/cpufreq/cpuinfo_max_freq;");
            }
            c.append("r ").append(GPU).append("thermal_pwrlevel;");
        }
        if (has("ram") || has("zram") || has("swap")) c.append("r /proc/meminfo;");
        if (has("zram") || has("swap")) c.append("r /proc/swaps;");
        if (has("zram")) c.append("r /sys/block/zram0/mm_stat;");
        if (has("swapio")) c.append("r /proc/vmstat;");
        if (has("psi")) c.append("r /proc/pressure/memory;");
        if (has("gpu")) {
            c.append("r ").append(GPU).append("devfreq/cur_freq;");
            c.append("r ").append(GPU).append("gpu_busy_percentage;");
            c.append("r ").append(GPU).append("gpubusy;");
        }
        if (has("temp")) {
            // Descobre quais "zonas térmicas" existem (1ª vez e a cada ~60 ciclos).
            if (zoneTypes.isEmpty() || tick % 60 == 1) {
                c.append("for z in /sys/class/thermal/thermal_zone*; do read t < \"$z/type\"; "
                        + "echo \"##Z ${z##*thermal_zone} $t\"; done;");
            }
            for (int z : tempZones) c.append("r /sys/class/thermal/thermal_zone").append(z).append("/temp;");
        }
        if (has("bat") || has("temp")) {
            c.append("r ").append(BAT).append("temp;");
        }
        if (has("bat")) {
            c.append("r ").append(BAT).append("capacity;");
            c.append("r ").append(BAT).append("current_now;");
            c.append("r ").append(BAT).append("status;");
        }

        // Qual é o app em primeiro plano? (processo com oom_score_adj = 0)
        boolean needTarget = has("app") || has("fps");
        if (needTarget && (targetPid <= 0 || tick % 5 == 0)) {
            c.append("echo '##SCAN'; for f in $(grep -l '^0$' /proc/[0-9]*/oom_score_adj 2>/dev/null); do "
                    + "p=${f#/proc/}; p=${p%%/*}; "
                    + "n=$(tr '\\000' ' ' < /proc/$p/cmdline 2>/dev/null); "
                    + "m=$(grep VmRSS /proc/$p/status 2>/dev/null); "
                    + "echo \"P $p ${n%% *} $m\"; done;");
        }
        if (has("app") && targetPid > 0) c.append("r /proc/").append(targetPid).append("/status;");

        if (has("fps") && targetPid > 0) {
            if (fpsLayer.isEmpty() || tick % 10 == 0) {
                c.append("echo '##LAYERS'; dumpsys SurfaceFlinger --list 2>/dev/null;");
            }
            if (!fpsLayer.isEmpty()) {
                c.append("echo '##LAT'; dumpsys SurfaceFlinger --latency '").append(fpsLayer).append("' 2>/dev/null;");
            }
        }

        // "Prioridade absoluta": o Android reescreve esse valor de tempos em tempos,
        // por isso gravamos de novo a cada ciclo. -1000 = nunca morto por falta de memória.
        if (ownPid > 0) c.append("echo -1000 > /proc/").append(ownPid).append("/oom_score_adj 2>/dev/null;");

        return c.toString();
    }

    // ------------------------------------------------------------------ leitura

    public List<Line> parse(String out, long nowMs, long selfRssKb, double selfCpuPct) {
        // Divide a saída em seções: "##nome" + linhas seguintes.
        Map<String, List<String>> sec = new HashMap<String, List<String>>();
        List<String> cur = null;
        for (String ln : out.split("\n")) {
            if (ln.startsWith("##")) {
                String h = ln.substring(2).trim();
                if (h.startsWith("Z ")) {
                    String[] p = h.split("\\s+", 3);
                    if (p.length == 3) {
                        try {
                            zoneTypes.put(Integer.parseInt(p[1]), p[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    cur = null;
                } else {
                    cur = new ArrayList<String>();
                    sec.put(h, cur);
                }
            } else if (cur != null) {
                cur.add(ln);
            }
        }
        if (has("temp")) chooseZones();
        if (has("app") || has("fps")) pickTarget(sec.get("SCAN"));

        List<Line> res = new ArrayList<Line>();
        List<String> mem = sec.get("/proc/meminfo");
        List<String> swaps = sec.get("/proc/swaps");

        // ---- RAM
        if (has("ram")) {
            long total = kv(mem, "MemTotal:"), avail = kv(mem, "MemAvailable:");
            if (total > 0) {
                int lv = avail < 300 * 1024 ? 2 : (avail < 600 * 1024 ? 1 : 0);
                res.add(new Line("RAM  " + gb(total - avail) + "/" + gb(total) + "  livre " + gb(avail), lv));
            } else {
                res.add(new Line("RAM  n/d", 3));
            }
        }

        // ---- ZRAM e swap em disco (de /proc/swaps)
        long zSize = 0, zUsed = 0, dSize = 0, dUsed = 0;
        boolean zOn = false, dOn = false;
        if (swaps != null) {
            for (String l : swaps) {
                String[] t = l.trim().split("\\s+");
                if (t.length < 5 || t[0].equals("Filename")) continue;
                long size = toLong(t[2]), used = toLong(t[3]);
                if (t[0].contains("zram")) {
                    zOn = true;
                    zSize += size;
                    zUsed += used;
                } else {
                    dOn = true;
                    dSize += size;
                    dUsed += used;
                }
            }
        }
        if (has("zram")) {
            if (!zOn) {
                res.add(new Line("ZRAM off", 3));
            } else {
                String extra = "";
                List<String> mm = sec.get("/sys/block/zram0/mm_stat");
                if (mm != null && !mm.isEmpty()) {
                    String[] t = mm.get(0).trim().split("\\s+");
                    if (t.length >= 3) {
                        double orig = toLong(t[0]), comp = toLong(t[1]), real = toLong(t[2]);
                        double ratio = comp > 0 ? orig / comp : 0;
                        extra = "  RAM real " + gb((long) (real / 1024)) + String.format(Locale.US, "  %.1f:1", ratio);
                    }
                }
                double pct = zSize > 0 ? 100.0 * zUsed / zSize : 0;
                int lv = pct > 90 ? 2 : (pct > 75 ? 1 : 0);
                res.add(new Line("ZRAM " + gb(zUsed) + "/" + gb(zSize) + extra, lv));
            }
        }
        if (has("swap")) {
            if (!dOn) {
                res.add(new Line("SWAP off", 3));
            } else {
                double pct = dSize > 0 ? 100.0 * dUsed / dSize : 0;
                res.add(new Line("SWAP " + gb(dUsed) + "/" + gb(dSize), pct > 50 ? 2 : (dUsed > 10 * 1024 ? 1 : 0)));
            }
        }

        // ---- Swap I/O (páginas de 4 KB trocadas por segundo)
        if (has("swapio")) {
            List<String> vm = sec.get("/proc/vmstat");
            long in = -1, outp = -1;
            if (vm != null) {
                for (String l : vm) {
                    if (l.startsWith("pswpin ")) in = toLong(l.substring(7));
                    else if (l.startsWith("pswpout ")) outp = toLong(l.substring(8));
                }
            }
            if (in >= 0 && prevSwpIn >= 0 && nowMs > prevVmMs) {
                double dt = (nowMs - prevVmMs) / 1000.0;
                double rin = (in - prevSwpIn) * 4.0 / 1024.0 / dt;
                double rout = (outp - prevSwpOut) * 4.0 / 1024.0 / dt;
                double worst = Math.max(rin, rout);
                res.add(new Line(String.format(Locale.US, "SWPIO  in %.1f  out %.1f MB/s", rin, rout),
                        worst > 30 ? 2 : (worst > 5 ? 1 : 0)));
            } else {
                res.add(new Line("SWPIO  --", 3));
            }
            if (in >= 0) {
                prevSwpIn = in;
                prevSwpOut = outp;
                prevVmMs = nowMs;
            }
        }

        // ---- PSI (pressão de memória: % do tempo parado esperando memória)
        if (has("psi")) {
            List<String> psi = sec.get("/proc/pressure/memory");
            if (psi == null || psi.isEmpty()) {
                res.add(new Line("PSI  n/d (kernel sem PSI)", 3));
            } else {
                double some = 0, full = 0;
                for (String l : psi) {
                    double v = avg10(l);
                    if (l.startsWith("some")) some = v;
                    else if (l.startsWith("full")) full = v;
                }
                int lv = (some >= 25 || full >= 5) ? 2 : (some >= 10 ? 1 : 0);
                res.add(new Line(String.format(Locale.US, "PSI  some %.1f  full %.1f", some, full), lv));
            }
        }

        // ---- CPU % e clocks
        if (has("cpu")) res.add(cpuLine(sec.get("/proc/stat")));
        if (has("cpufreq")) {
            res.add(new Line("S " + freqRow(sec, 0, 4), 0));
            res.add(new Line("G " + freqRow(sec, 4, 8), 0));
        }

        // ---- GPU
        if (has("gpu")) res.add(gpuLine(sec));

        // ---- Limites (throttling)
        if (has("limit")) res.add(limitLine(sec));

        // ---- Temperaturas
        if (has("temp")) res.add(tempLine(sec));

        // ---- Bateria
        if (has("bat")) res.add(batLine(sec));

        // ---- App em foco
        if (has("app")) {
            if (targetPid > 0 && targetName.length() > 0) {
                List<String> st = sec.get("/proc/" + targetPid + "/status");
                long rss = kv(st, "VmRSS:"), swp = kv(st, "VmSwap:");
                res.add(new Line("APP  " + shortName(targetName) + "  " + gb(rss) + "  swap " + gb(swp),
                        (rss > 0 && swp > rss) ? 1 : 0));
            } else {
                res.add(new Line("APP  --", 3));
            }
        }

        // ---- FPS (experimental)
        if (has("fps")) res.add(fpsLine(sec));

        // ---- Custo do próprio overlay
        if (has("self")) {
            res.add(new Line(String.format(Locale.US, "OVL  %d MB  %.1f%% cpu", selfRssKb / 1024, selfCpuPct), 3));
        }

        if (res.isEmpty()) res.add(new Line("(nenhuma métrica ligada)", 3));
        return res;
    }

    // ------------------------------------------------------------------ linhas

    private Line cpuLine(List<String> st) {
        if (st == null) return new Line("CPU  n/d", 3);
        double[] pct = new double[9]; // 0 = total, 1..8 = cpu0..cpu7
        boolean[] ok = new boolean[9];
        long sT = 0, sI = 0, gT = 0, gI = 0;
        for (String l : st) {
            if (!l.startsWith("cpu")) continue;
            String[] t = l.trim().split("\\s+");
            String name = t[0];
            if (t.length < 8) continue;
            long total = 0;
            for (int i = 1; i <= 8 && i < t.length; i++) total += toLong(t[i]);
            long idle = toLong(t[4]) + toLong(t[5]);
            long[] prev = prevCpu.get(name);
            prevCpu.put(name, new long[]{total, idle});
            if (prev == null) continue;
            long dT = total - prev[0], dI = idle - prev[1];
            if (dT <= 0) continue;
            int idx = name.equals("cpu") ? 0 : cpuIndex(name) + 1;
            if (idx < 0 || idx > 8) continue;
            pct[idx] = 100.0 * (dT - dI) / dT;
            ok[idx] = true;
            if (idx >= 1 && idx <= 4) {
                sT += dT;
                sI += dI;
            } else if (idx >= 5) {
                gT += dT;
                gI += dI;
            }
        }
        if (!ok[0]) return new Line("CPU  --", 3);
        double s = sT > 0 ? 100.0 * (sT - sI) / sT : 0;
        double g = gT > 0 ? 100.0 * (gT - gI) / gT : 0;
        int lv = pct[0] > 95 ? 1 : 0;
        return new Line(String.format(Locale.US, "CPU  %.0f%%   S %.0f%%  G %.0f%%", pct[0], s, g), lv);
    }

    private String freqRow(Map<String, List<String>> sec, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            List<String> l = sec.get(CPU + i + "/cpufreq/scaling_cur_freq");
            if (sb.length() > 0) sb.append(' ');
            if (l == null || l.isEmpty()) sb.append(" off");
            else sb.append(String.format(Locale.US, "%4d", toLong(l.get(0)) / 1000));
        }
        return sb.toString();
    }

    private Line gpuLine(Map<String, List<String>> sec) {
        List<String> f = sec.get(GPU + "devfreq/cur_freq");
        if (f == null || f.isEmpty()) return new Line("GPU  n/d", 3);
        long mhz = toLong(f.get(0)) / 1000000;
        int busy = -1;
        List<String> p = sec.get(GPU + "gpu_busy_percentage");
        if (p != null && !p.isEmpty()) {
            busy = (int) firstNumber(p.get(0));
        } else {
            List<String> b = sec.get(GPU + "gpubusy");
            if (b != null && !b.isEmpty()) {
                String[] t = b.get(0).trim().split("\\s+");
                if (t.length >= 2) {
                    long bu = toLong(t[0]), to = toLong(t[1]);
                    if (to > 0) busy = (int) (100 * bu / to);
                }
            }
        }
        return new Line("GPU  " + mhz + " MHz  " + (busy >= 0 ? busy + "%" : "--"), 0);
    }

    private Line limitLine(Map<String, List<String>> sec) {
        StringBuilder sb = new StringBuilder();
        int lv = 0;
        long[] a = {limited(sec, 0), limited(sec, 4)};
        String[] nm = {"S", "G"};
        for (int i = 0; i < 2; i++) {
            if (a[i] > 0) {
                sb.append(nm[i]).append("<").append(a[i]).append(" ");
                lv = 1;
            }
        }
        List<String> tp = sec.get(GPU + "thermal_pwrlevel");
        if (tp != null && !tp.isEmpty() && toLong(tp.get(0)) > 0) {
            sb.append("GPU-térmico ");
            lv = 2;
        }
        if (sb.length() == 0) return new Line("LIM  nenhum", 3);
        return new Line("LIM  " + sb.toString().trim(), lv);
    }

    /** Devolve o teto atual (MHz) se estiver abaixo do máximo do chip; senão 0. */
    private long limited(Map<String, List<String>> sec, int cpu) {
        List<String> mx = sec.get(CPU + cpu + "/cpufreq/scaling_max_freq");
        List<String> hw = sec.get(CPU + cpu + "/cpufreq/cpuinfo_max_freq");
        if (mx == null || hw == null || mx.isEmpty() || hw.isEmpty()) return 0;
        long a = toLong(mx.get(0)), b = toLong(hw.get(0));
        return (a > 0 && b > 0 && a < b) ? a / 1000 : 0;
    }

    private void chooseZones() {
        tempZones.clear();
        int cpuN = 0, gpuN = 0;
        for (Map.Entry<Integer, String> e : zoneTypes.entrySet()) {
            String t = e.getValue();
            if (t.equals("msm_therm")) {
                tempZones.add(e.getKey());
            } else if (t.startsWith("cpu") && cpuN < 8) {
                tempZones.add(e.getKey());
                cpuN++;
            } else if (t.contains("gpu") && gpuN < 3) {
                tempZones.add(e.getKey());
                gpuN++;
            }
        }
    }

    private Line tempLine(Map<String, List<String>> sec) {
        double cpu = -1, gpu = -1, pcb = -1, bat = -1;
        for (int z : tempZones) {
            List<String> l = sec.get("/sys/class/thermal/thermal_zone" + z + "/temp");
            if (l == null || l.isEmpty()) continue;
            double v = normTemp(toLong(l.get(0)));
            String t = zoneTypes.get(z);
            if (t == null) continue;
            if (t.equals("msm_therm")) pcb = v;
            else if (t.startsWith("cpu")) cpu = Math.max(cpu, v);
            else if (t.contains("gpu")) gpu = Math.max(gpu, v);
        }
        List<String> bt = sec.get(BAT + "temp");
        if (bt != null && !bt.isEmpty()) {
            long raw = toLong(bt.get(0));
            bat = raw > 100 ? raw / 10.0 : raw;
        }
        int lv = 0;
        StringBuilder sb = new StringBuilder("TEMP ");
        if (cpu >= 0) {
            sb.append(String.format(Locale.US, " CPU %.0f", cpu));
            lv = Math.max(lv, cpu >= 85 ? 2 : (cpu >= 70 ? 1 : 0));
        }
        if (gpu >= 0) {
            sb.append(String.format(Locale.US, " GPU %.0f", gpu));
            lv = Math.max(lv, gpu >= 85 ? 2 : (gpu >= 70 ? 1 : 0));
        }
        if (pcb >= 0) {
            sb.append(String.format(Locale.US, " PCB %.0f", pcb));
            lv = Math.max(lv, pcb >= 52 ? 2 : (pcb >= 45 ? 1 : 0));
        }
        if (bat >= 0) {
            sb.append(String.format(Locale.US, " BAT %.0f", bat));
            lv = Math.max(lv, bat >= 45 ? 2 : (bat >= 40 ? 1 : 0));
        }
        if (cpu < 0 && gpu < 0 && pcb < 0 && bat < 0) return new Line("TEMP  n/d", 3);
        return new Line(sb.toString().replace("TEMP  ", "TEMP "), lv);
    }

    private Line batLine(Map<String, List<String>> sec) {
        List<String> cap = sec.get(BAT + "capacity");
        List<String> cur = sec.get(BAT + "current_now");
        List<String> st = sec.get(BAT + "status");
        if (cap == null || cap.isEmpty()) return new Line("BAT  n/d", 3);
        long c = cur != null && !cur.isEmpty() ? toLong(cur.get(0)) : 0;
        long ma = Math.abs(c) > 20000 ? c / 1000 : c;
        String s = st != null && !st.isEmpty() ? st.get(0).trim() : "";
        String sh = s.startsWith("Charging") ? "carregando" : s.startsWith("Discharging") ? "descarga"
                : s.startsWith("Full") ? "cheia" : s.toLowerCase(Locale.US);
        return new Line("BAT  " + toLong(cap.get(0)) + "%  " + ma + " mA  " + sh, 0);
    }

    private Line fpsLine(Map<String, List<String>> sec) {
        // 1) escolhe a camada (layer) do app em foco
        List<String> layers = sec.get("LAYERS");
        if (layers != null && targetName.length() > 0) {
            String best = "";
            for (String l : layers) {
                if (!l.contains(targetName) || l.startsWith("Background")) continue;
                if (l.startsWith("SurfaceView")) {
                    best = l;
                    break;
                }
                if (best.isEmpty()) best = l;
            }
            if (!best.isEmpty() && !best.contains("'")) fpsLayer = best.trim();
        }
        // 2) conta quadros apresentados no último segundo
        List<String> lat = sec.get("LAT");
        if (lat == null || lat.size() < 2) {
            if (fpsLayer.length() > 0 && lat != null) fpsLayer = ""; // camada sumiu: procurar de novo
            return new Line("FPS  --", 3);
        }
        long tmax = -1;
        List<Long> ts = new ArrayList<Long>();
        for (int i = 1; i < lat.size(); i++) {
            String[] p = lat.get(i).trim().split("\\s+");
            if (p.length < 3) continue;
            long t = toLong(p[1]);
            if (t <= 0 || t == Long.MAX_VALUE) continue;
            ts.add(t);
            if (t > tmax) tmax = t;
        }
        if (ts.isEmpty()) return new Line("FPS  --", 3);
        int n = 0;
        for (long t : ts) {
            if (t > tmax - 1000000000L) n++;
        }
        if (tmax == prevFrameT) n = 0; // nenhum quadro novo desde o ciclo anterior
        prevFrameT = tmax;
        return new Line("FPS  " + n, n > 0 && n < 25 ? 1 : 0);
    }

    // ------------------------------------------------------------------ alvo (app em foco)

    private void pickTarget(List<String> scan) {
        if (scan == null) return;
        int bestPid = 0;
        String bestName = "";
        long bestRss = -1;
        for (String l : scan) {
            String[] t = l.trim().split("\\s+");
            if (t.length < 3 || !t[0].equals("P")) continue;
            String name = t[2];
            if (name.indexOf('.') < 0 || name.startsWith("/") || name.equals(selfPkg)) continue;
            long rss = 0;
            for (int i = 3; i < t.length - 1; i++) {
                if (t[i].equals("VmRSS:")) rss = toLong(t[i + 1]);
            }
            if (rss > bestRss) {
                bestRss = rss;
                bestPid = (int) toLong(t[1]);
                bestName = name;
            }
        }
        if (bestPid > 0) {
            if (!bestName.equals(targetName)) {
                fpsLayer = "";
                prevFrameT = -1;
            }
            targetPid = bestPid;
            targetName = bestName;
        } else {
            targetPid = 0;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static int cpuIndex(String name) {
        try {
            return Integer.parseInt(name.substring(3));
        } catch (Exception e) {
            return -1;
        }
    }

    private static double normTemp(long v) {
        if (v >= 1000) return v / 1000.0;
        if (v >= 200) return v / 10.0;
        return v;
    }

    private static double avg10(String line) {
        int i = line.indexOf("avg10=");
        if (i < 0) return 0;
        int j = line.indexOf(' ', i);
        String s = j < 0 ? line.substring(i + 6) : line.substring(i + 6, j);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Valor numérico (em kB) de uma linha "Chave:   123 kB". */
    private static long kv(List<String> lines, String key) {
        if (lines == null) return 0;
        for (String l : lines) {
            if (l.startsWith(key)) return firstNumber(l.substring(key.length()));
        }
        return 0;
    }

    private static long firstNumber(String s) {
        StringBuilder d = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                d.append(ch);
                started = true;
            } else if (started) {
                break;
            }
        }
        return d.length() == 0 ? 0 : toLong(d.toString());
    }

    private static long toLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** kB -> "1.9G" ou "512M". */
    private static String gb(long kb) {
        if (kb >= 1000 * 1024) return String.format(Locale.US, "%.1fG", kb / 1048576.0);
        return (kb / 1024) + "M";
    }

    private static String shortName(String pkg) {
        String[] p = pkg.split("\\.");
        if (p.length <= 2) return pkg;
        return p[p.length - 2] + "." + p[p.length - 1];
    }
}
