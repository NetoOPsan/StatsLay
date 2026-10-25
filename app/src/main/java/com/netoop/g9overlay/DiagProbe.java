package com.netoop.g9overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Diagnóstico automático: lê de tempos em tempos os "botões" que limitam CPU/GPU (governors, tetos,
 * níveis térmicos, cooling devices), sensores e memória, e devolve linhas prontas para o log:
 *   SNAP    : foto compacta de tudo (a cada intervalo)
 *   MUDOU   : o que mudou entre uma foto e a anterior (mostra QUEM limitou e QUANDO)
 *   ALERTA  : GPU abaixo do máximo com carga alta, com a provável explicação
 *   DMESG   : linhas novas do kernel sobre kgsl/gpu/thermal/lmk
 * Sem dependência do Android (dá para testar no PC).
 */
public class DiagProbe {

    private final String gpu, cpuBase, thermal, bat;

    private long lastSnapMs = 0, lastDmesgMs = 0, lastAlertMs = 0;
    private Map<String, String> prevSig = null;
    private int lowStreak = 0;
    private String lastDmesgSig = "";

    private final Set<String> bad = new HashSet<String>();
    private final Map<String, Integer> hangCount = new HashMap<String, Integer>();
    private long pausedUntil = 0;

    public DiagProbe() {
        this("/sys/class/kgsl/kgsl-3d0/", "/sys/devices/system/cpu/", "/sys/class/thermal/",
                "/sys/class/power_supply/battery/");
    }

    public DiagProbe(String gpu, String cpuBase, String thermal, String bat) {
        this.gpu = gpu;
        this.cpuBase = cpuBase;
        this.thermal = thermal;
        this.bat = bat;
    }

    public boolean due(long now, int intervalSec) {
        return now >= pausedUntil && now - lastSnapMs >= intervalSec * 1000L - 200;
    }

    private void rd(StringBuilder c, String path) {
        if (!bad.contains(path)) c.append("g9r ").append(path).append(';');
    }

    // ------------------------------------------------------------------ comando

    /** full=true: também lê o dmesg (usado no botão "Registrar agora"). */
    public String buildCommand(long now, boolean full) {
        StringBuilder c = new StringBuilder();
        String[] g = {"devfreq/governor", "devfreq/min_freq", "devfreq/max_freq", "devfreq/cur_freq",
                "devfreq/available_frequencies", "thermal_pwrlevel", "min_pwrlevel", "max_pwrlevel",
                "default_pwrlevel", "max_gpuclk", "gpuclk", "force_clk_on", "force_bus_on", "force_rail_on",
                "force_no_nap", "idle_timer", "gpu_busy_percentage", "gpubusy"};
        for (String f : g) rd(c, gpu + f);
        int[] clusters = {0, 4};
        String[] cf = {"scaling_governor", "scaling_min_freq", "scaling_max_freq", "scaling_cur_freq", "cpuinfo_max_freq"};
        for (int cl : clusters) for (String f : cf) rd(c, cpuBase + "cpu" + cl + "/cpufreq/" + f);
        rd(c, cpuBase + "online");
        rd(c, bat + "temp");
        rd(c, bat + "capacity");
        rd(c, bat + "status");
        rd(c, "/proc/meminfo");
        rd(c, "/proc/pressure/memory");
        if (!bad.contains("TZ")) {
            c.append("for z in ").append(thermal).append("thermal_zone*; do t=; v=; read t < \"$z/type\"; read v < \"$z/temp\"; "
                    + "echo \"##TZ ${z##*thermal_zone} $t $v\"; done;");
        }
        if (!bad.contains("CD")) {
            c.append("for k in ").append(thermal).append("cooling_device*; do t=; a=; m=; read t < \"$k/type\"; "
                    + "read a < \"$k/cur_state\"; read m < \"$k/max_state\"; "
                    + "echo \"##CD ${k##*cooling_device} $t $a $m\"; done;");
        }
        if (full || now - lastDmesgMs >= 60000) {
            lastDmesgMs = now;
            c.append("echo '##DMESG'; g9t 3 dmesg | grep -iE 'kgsl|adreno|gpu fault|thermal|lowmemory|lmkd|oom' | tail -n 6;");
        }
        return c.toString();
    }

    /** Cabeçalhos que pertencem ao diagnóstico (para saber quem travou). */
    public static boolean ownsHeader(String h) {
        if (h == null) return false;
        return h.startsWith("/sys/") || h.startsWith("/proc/") || h.startsWith("TZ ") || h.startsWith("CD ")
                || h.equals("DMESG");
    }

    /** Travou lendo `header`: 2 travamentos seguidos no mesmo arquivo = ele é ignorado; no geral, pausa 2 min. */
    public String noteHang(String header, long now) {
        String key = header.startsWith("TZ ") ? "TZ" : header.startsWith("CD ") ? "CD" : header;
        int n = hangCount.containsKey(key) ? hangCount.get(key) + 1 : 1;
        hangCount.put(key, n);
        if (n >= 2) {
            bad.add(key);
            hangCount.put(key, 0);
            return "o diagnóstico travou 2x em '" + key + "': passo a ignorar esse item";
        }
        return null;
    }

    public void pause(long now) {
        pausedUntil = now + 120000;
    }

    // ------------------------------------------------------------------ leitura

    public List<String> parse(String out, long now) {
        lastSnapMs = now;
        hangCount.clear();
        List<String> res = new ArrayList<String>();
        Map<String, List<String>> sec = new HashMap<String, List<String>>();
        List<String[]> tz = new ArrayList<String[]>();
        List<String[]> cd = new ArrayList<String[]>();
        List<String> cur = null;
        for (String ln : out.split("\n")) {
            if (ln.startsWith("##")) {
                String h = ln.substring(2).trim();
                if (h.startsWith("TZ ")) {
                    tz.add(h.split("\\s+"));
                    cur = null;
                } else if (h.startsWith("CD ")) {
                    cd.add(h.split("\\s+"));
                    cur = null;
                } else {
                    cur = new ArrayList<String>();
                    sec.put(h, cur);
                }
            } else if (cur != null) {
                cur.add(ln);
            }
        }

        // ---- GPU
        String gGov = val(sec, gpu + "devfreq/governor");
        long gCur = num(val(sec, gpu + "devfreq/cur_freq")), gMin = num(val(sec, gpu + "devfreq/min_freq")),
                gMax = num(val(sec, gpu + "devfreq/max_freq"));
        long hw = 0;
        String av = val(sec, gpu + "devfreq/available_frequencies");
        if (av != null) for (String t : av.trim().split("\\s+")) hw = Math.max(hw, num(t));
        String tpl = val(sec, gpu + "thermal_pwrlevel"), pMin = val(sec, gpu + "min_pwrlevel"),
                pMax = val(sec, gpu + "max_pwrlevel"), pDef = val(sec, gpu + "default_pwrlevel");
        int busy = busy(sec);

        // ---- CPU (um cluster silver = cpu0, um gold = cpu4)
        String[] cname = {"S", "G"};
        int[] cidx = {0, 4};
        String[] cgov = new String[2];
        long[] ccur = new long[2], cmin = new long[2], cmax = new long[2], chw = new long[2];
        for (int i = 0; i < 2; i++) {
            String b = cpuBase + "cpu" + cidx[i] + "/cpufreq/";
            cgov[i] = val(sec, b + "scaling_governor");
            ccur[i] = num(val(sec, b + "scaling_cur_freq"));
            cmin[i] = num(val(sec, b + "scaling_min_freq"));
            cmax[i] = num(val(sec, b + "scaling_max_freq"));
            chw[i] = num(val(sec, b + "cpuinfo_max_freq"));
        }
        String online = val(sec, cpuBase + "online");

        // ---- temperaturas
        double tCpu = -1, tGpu = -1, tPcb = -1, tBat = -1;
        for (String[] t : tz) {
            if (t.length < 4) continue;
            String type = join(t, 2, t.length - 1);
            double v = norm(num(t[t.length - 1]));
            if (type.equals("msm_therm")) tPcb = v;
            else if (type.startsWith("cpu")) tCpu = Math.max(tCpu, v);
            else if (type.contains("gpu")) tGpu = Math.max(tGpu, v);
        }
        String bt = val(sec, bat + "temp");
        if (bt != null) {
            long raw = num(bt);
            tBat = raw > 100 ? raw / 10.0 : raw;
        }

        // ---- cooling devices (mitigações do kernel/thermal-engine)
        Map<String, String> sig = new LinkedHashMap<String, String>();
        List<String> active = new ArrayList<String>();
        boolean gpuCdev = false;
        for (String[] k : cd) {
            if (k.length < 5) continue;
            String type = join(k, 2, k.length - 2);
            long a = num(k[k.length - 2]), m = num(k[k.length - 1]);
            sig.put("cdev" + k[1] + ":" + type, String.valueOf(a));
            if (a > 0) {
                active.add(type + "=" + a + "/" + m);
                if (type.contains("gpu") || type.contains("devfreq")) gpuCdev = true;
            }
        }

        // ---- memória
        long avail = 0, swapFree = 0;
        List<String> mi = sec.get("/proc/meminfo");
        if (mi != null) {
            for (String l : mi) {
                if (l.startsWith("MemAvailable:")) avail = first(l);
                else if (l.startsWith("SwapFree:")) swapFree = first(l);
            }
        }
        String psi = "n/d";
        List<String> pl = sec.get("/proc/pressure/memory");
        if (pl != null && !pl.isEmpty()) {
            String some = "?", full = "?";
            for (String l : pl) {
                if (l.startsWith("some")) some = avg10(l);
                else if (l.startsWith("full")) full = avg10(l);
            }
            psi = some + "/" + full;
        }

        // ---- assinatura dos limites (o que NÃO oscila sozinho): serve para detectar mudanças
        sig.put("gpu.governor", gGov);
        sig.put("gpu.min_freq", mhzHz(gMin));
        sig.put("gpu.max_freq", mhzHz(gMax));
        sig.put("gpu.thermal_pwrlevel", tpl);
        sig.put("gpu.min_pwrlevel", pMin);
        sig.put("gpu.max_pwrlevel", pMax);
        sig.put("gpu.force_clk_on", val(sec, gpu + "force_clk_on"));
        sig.put("gpu.force_bus_on", val(sec, gpu + "force_bus_on"));
        sig.put("gpu.force_rail_on", val(sec, gpu + "force_rail_on"));
        sig.put("gpu.force_no_nap", val(sec, gpu + "force_no_nap"));
        for (int i = 0; i < 2; i++) {
            sig.put("cpu" + cidx[i] + ".governor", cgov[i]);
            sig.put("cpu" + cidx[i] + ".min", mhzKhz(cmin[i]));
            sig.put("cpu" + cidx[i] + ".max", mhzKhz(cmax[i]));
        }
        sig.put("cpu.online", online);

        // ---- MUDOU
        if (prevSig != null) {
            StringBuilder ch = new StringBuilder();
            Set<String> keys = new java.util.LinkedHashSet<String>(prevSig.keySet());
            keys.addAll(sig.keySet());
            for (String k : keys) {
                String a = prevSig.get(k), b = sig.get(k);
                if (a == null ? b != null : !a.equals(b)) ch.append(k).append(' ').append(a).append("→").append(b).append(" | ");
            }
            if (ch.length() > 0) res.add("MUDOU: " + ch.toString().replaceAll(" \\| $", ""));
        }
        prevSig = sig;

        // ---- SNAP
        StringBuilder sn = new StringBuilder("SNAP ");
        sn.append(String.format(Locale.US, "gpu=%sMHz busy=%s gov=%s min=%s max=%s hw=%s tpl=%s pwr(min/max/def)=%s/%s/%s",
                mhzHz(gCur), busy < 0 ? "?" : busy + "%", gGov, mhzHz(gMin), mhzHz(gMax), mhzHz(hw), tpl, pMin, pMax, pDef));
        for (int i = 0; i < 2; i++) {
            sn.append(String.format(Locale.US, " | %s %s cur=%s max=%s/%s", cname[i], cgov[i], mhzKhz(ccur[i]), mhzKhz(cmax[i]), mhzKhz(chw[i])));
        }
        sn.append(" | on=").append(online);
        sn.append(String.format(Locale.US, " | T cpu=%.0f gpu=%.0f pcb=%.0f bat=%.0f", tCpu, tGpu, tPcb, tBat));
        sn.append(" | cdev ativos: ").append(active.isEmpty() ? "nenhum" : join(active.toArray(new String[0]), 0, active.size()));
        sn.append(String.format(Locale.US, " | RAM livre=%dM swapfree=%dM psi=%s", avail / 1024, swapFree / 1024, psi));
        res.add(sn.toString());

        // ---- ALERTA: GPU abaixo do máximo com carga alta
        boolean low = hw > 0 && gCur > 0 && gCur < hw * 0.9 && busy >= 60;
        lowStreak = low ? lowStreak + 1 : 0;
        if (lowStreak >= 3 && now - lastAlertMs >= 60000) {
            lastAlertMs = now;
            String hint;
            long tplN = num(tpl);
            if (gMax > 0 && hw > 0 && gMax < hw) {
                hint = "o TETO do devfreq foi baixado (max_freq=" + mhzHz(gMax) + " < " + mhzHz(hw) + " MHz): algo está limitando a GPU";
            } else if (tplN > 0) {
                hint = "limite TÉRMICO do kgsl ativo (thermal_pwrlevel=" + tpl + ")";
            } else if (gpuCdev) {
                hint = "cooling device de GPU ativo: " + join(active.toArray(new String[0]), 0, active.size());
            } else if (gMin > 0 && gMin >= hw) {
                hint = "o piso está no máximo mas o clock não acompanha (possível limite fora do devfreq)";
            } else {
                hint = "nenhum limite visível: o governor " + gGov + " escolheu esse clock (a GPU não está saturada; pode estar esperando a CPU)";
            }
            res.add(String.format(Locale.US, "ALERTA: GPU em %sMHz (máx %sMHz) com %d%% de carga há %d leituras → %s",
                    mhzHz(gCur), mhzHz(hw), busy, lowStreak, hint));
        }

        // ---- DMESG (só linhas novas)
        List<String> dm = sec.get("DMESG");
        if (dm != null) {
            List<String> lines = new ArrayList<String>();
            for (String l : dm) if (l.trim().length() > 0) lines.add(l.trim());
            String dsig = lines.isEmpty() ? "" : lines.size() + "|" + lines.get(lines.size() - 1);
            if (!dsig.equals(lastDmesgSig) && !lines.isEmpty()) {
                lastDmesgSig = dsig;
                for (String l : lines) res.add("DMESG: " + (l.length() > 220 ? l.substring(0, 220) : l));
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ utilidades

    private int busy(Map<String, List<String>> sec) {
        String p = val(sec, gpu + "gpu_busy_percentage");
        if (p != null) return (int) first(p);
        String b = val(sec, gpu + "gpubusy");
        if (b != null) {
            String[] t = b.trim().split("\\s+");
            if (t.length >= 2) {
                long bu = num(t[0]), to = num(t[1]);
                if (to > 0) return (int) (100 * bu / to);
            }
        }
        return -1;
    }

    private static String val(Map<String, List<String>> sec, String path) {
        List<String> l = sec.get(path);
        if (l == null || l.isEmpty()) return null;
        return l.get(0).trim();
    }

    private static String mhzHz(long hz) {
        return hz <= 0 ? "?" : String.valueOf(hz / 1000000);
    }

    private static String mhzKhz(long khz) {
        return khz <= 0 ? "?" : String.valueOf(khz / 1000);
    }

    private static double norm(long v) {
        if (v >= 1000) return v / 1000.0;
        if (v >= 200) return v / 10.0;
        return v;
    }

    private static String avg10(String line) {
        int i = line.indexOf("avg10=");
        if (i < 0) return "?";
        int j = line.indexOf(' ', i);
        return j < 0 ? line.substring(i + 6) : line.substring(i + 6, j);
    }

    private static String join(String[] a, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < a.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static long first(String s) {
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
        return d.length() == 0 ? 0 : num(d.toString());
    }

    private static long num(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
