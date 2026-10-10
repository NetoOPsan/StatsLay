package com.netoop.g9overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parte LENTA e mais arriscada das métricas: descobrir o app em foco e medir o FPS.
 * Roda num shell root separado: se travar, só ela é afetada (o overlay principal continua).
 * Não depende do Android (dá para testar no PC).
 *
 * O nome do app vem de /data/system/packages.list (pacote <-> uid), SEM ler /proc/PID/cmdline:
 * ler o cmdline de um processo que está trocando páginas com o swap pode bloquear por muito tempo.
 */
public class SlowProbe {

    private final String selfPkg;
    private final String packagesList;
    private final int minUid;

    public volatile int targetPid = 0;
    public volatile String targetName = "";
    public volatile int fps = -1; // -1 = sem dado

    private final Map<Integer, String> uidMap = new HashMap<Integer, String>();
    private String fpsLayer = "";
    private long prevFrameT = -1;
    private long lastScanMs = 0, lastPkgMs = 0, lastLayersMs = 0;

    // pausa (backoff) depois de travamentos repetidos
    private final Map<String, Integer> hangCount = new HashMap<String, Integer>();
    private final Map<String, Long> blockedUntil = new HashMap<String, Long>();

    private final List<String> notes = new ArrayList<String>();

    public SlowProbe(String selfPkg) {
        this(selfPkg, "/data/system/packages.list", 10000);
    }

    public SlowProbe(String selfPkg, String packagesList, int minUid) {
        this.selfPkg = selfPkg;
        this.packagesList = packagesList;
        this.minUid = minUid;
    }

    private boolean blocked(String key, long now) {
        Long u = blockedUntil.get(key);
        return u != null && now < u;
    }

    private void note(String s) {
        synchronized (notes) {
            notes.add(s);
        }
    }

    public List<String> drainNotes() {
        synchronized (notes) {
            List<String> r = new ArrayList<String>(notes);
            notes.clear();
            return r;
        }
    }

    // ------------------------------------------------------------------ comando

    public String buildCommand(boolean wantApp, boolean wantFps, long now) {
        if (!wantApp && !wantFps) return "";
        StringBuilder c = new StringBuilder();

        if (!blocked("SCAN", now) && now - lastScanMs >= (targetPid <= 0 ? 3000 : 5000)) {
            lastScanMs = now;
            if (uidMap.isEmpty() || now - lastPkgMs >= 60000) {
                lastPkgMs = now;
                c.append("g9r ").append(packagesList).append(';');
            }
            // Candidatos = processos com oom_score_adj 0 (o app na tela). Para cada um: uid e VmRSS.
            c.append("echo '##SCAN'; for f in $(g9t 3 grep -l '^0$' /proc/[0-9]*/oom_score_adj); do "
                    + "p=${f#/proc/}; p=${p%%/*}; echo \"##SP $p\"; "
                    + "u=$(g9t 2 grep '^Uid:' /proc/$p/status); "
                    + "m=$(g9t 2 grep VmRSS /proc/$p/status); "
                    + "echo \"P $p $u $m\"; done;");
            // Plano B (só enquanto não há alvo): pacote da janela com foco + pid dele.
            // Ex.: mCurrentFocus=Window{1a2b u0 dev.eden.eden_emulator/...Activity}
            if (targetPid <= 0) {
                c.append("fp=$(g9t 4 dumpsys window | grep mCurrentFocus | sed -n 's#.* u[0-9]* \\([^/ }]*\\).*#\\1#p'); "
                        + "echo '##FOCUS'; if [ -n \"$fp\" ]; then set -- $(pidof \"$fp\"); echo \"FP $fp $1\"; fi;");
            }
        }

        if (wantFps && targetPid > 0 && !blocked("FPS", now)) {
            if (fpsLayer.isEmpty() || now - lastLayersMs >= 10000) {
                lastLayersMs = now;
                c.append("echo '##LAYERS'; g9t 4 dumpsys SurfaceFlinger --list;");
            }
            if (!fpsLayer.isEmpty()) {
                c.append("echo '##LAT'; g9t 4 dumpsys SurfaceFlinger --latency '").append(fpsLayer).append("';");
            }
        }
        return c.toString();
    }

    /** Chamado quando um ciclo lento travou. Depois de 2 travamentos seguidos, pausa essa parte por 2 min. */
    public void noteHang(String header, long now) {
        String key = "SCAN";
        if (header != null && (header.equals("LAYERS") || header.equals("LAT"))) key = "FPS";
        int n = hangCount.containsKey(key) ? hangCount.get(key) + 1 : 1;
        hangCount.put(key, n);
        if (n >= 2) {
            blockedUntil.put(key, now + 120000);
            hangCount.put(key, 0);
            note("travou 2x seguidas em " + key + " (ultimo=" + header + "): pausando por 120 s");
        }
    }

    // ------------------------------------------------------------------ leitura

    public void parse(String out, long now) {
        hangCount.clear();
        Map<String, List<String>> sec = new HashMap<String, List<String>>();
        List<String> cur = null;
        List<String> pLines = new ArrayList<String>(); // linhas "P pid Uid: ... VmRSS: ..." (ficam sob vários "##SP n")
        for (String ln : out.split("\n")) {
            if (ln.startsWith("##")) {
                cur = new ArrayList<String>();
                sec.put(ln.substring(2).trim(), cur);
            } else {
                if (ln.startsWith("P ")) pLines.add(ln);
                if (cur != null) cur.add(ln);
            }
        }

        List<String> pk = sec.get(packagesList);
        if (pk != null) {
            uidMap.clear();
            for (String l : pk) {
                String[] t = l.trim().split("\\s+");
                if (t.length < 2) continue;
                try {
                    int uid = Integer.parseInt(t[1]);
                    if (!uidMap.containsKey(uid)) uidMap.put(uid, t[0]);
                } catch (NumberFormatException ignored) {
                }
            }
            note("packages.list: " + uidMap.size() + " entradas");
        }

        if (sec.containsKey("SCAN")) pickTarget(pLines);
        if (targetPid <= 0 && sec.containsKey("FOCUS")) useFocus(sec.get("FOCUS"));

        if (sec.containsKey("LAYERS")) chooseLayer(sec.get("LAYERS"));
        if (sec.containsKey("LAT")) computeFps(sec.get("LAT"));
    }

    private void pickTarget(List<String> scan) {
        int bestPid = 0;
        String bestName = "";
        long bestRss = -1;
        int cand = 0;
        StringBuilder desc = new StringBuilder();
        for (String l : scan) {
            String[] t = l.trim().split("\\s+");
            if (t.length < 3 || !t[0].equals("P")) continue;
            cand++;
            long uid = -1, rss = 0;
            for (int i = 2; i < t.length - 1; i++) {
                if (t[i].equals("Uid:")) uid = num(t[i + 1]);
                if (t[i].equals("VmRSS:")) rss = num(t[i + 1]);
            }
            String pkg = uid >= minUid ? uidMap.get((int) uid) : null;
            desc.append(t[1]).append('/').append(uid).append('/').append(pkg == null ? "-" : pkg).append(' ');
            if (pkg == null || pkg.equals(selfPkg)) continue;
            if (rss > bestRss) {
                bestRss = rss;
                bestPid = (int) num(t[1]);
                bestName = pkg;
            }
        }
        if (!bestName.equals(targetName) || bestPid != targetPid) {
            note("alvo: " + (bestName.isEmpty() ? "(nenhum)" : bestName + " pid=" + bestPid)
                    + " | " + cand + " proc(s) com adj 0, uidMap=" + uidMap.size() + " entradas"
                    + " | candidatos(pid/uid/pacote): " + desc.toString().trim());
        }
        if (bestPid > 0) {
            if (!bestName.equals(targetName)) {
                fpsLayer = "";
                prevFrameT = -1;
                fps = -1;
            }
            targetPid = bestPid;
            targetName = bestName;
        } else {
            targetPid = 0;
            targetName = "";
            fps = -1;
        }
    }

    /** Plano B: usa o pacote da janela com foco (dumpsys window) quando a varredura não achou nenhum app. */
    private void useFocus(List<String> focus) {
        for (String l : focus) {
            String[] t = l.trim().split("\\s+");
            if (t.length < 3 || !t[0].equals("FP")) continue;
            long pid = num(t[2]);
            if (pid <= 0 || t[1].equals(selfPkg)) continue;
            if (!t[1].equals(targetName)) {
                fpsLayer = "";
                prevFrameT = -1;
                fps = -1;
            }
            targetPid = (int) pid;
            targetName = t[1];
            note("alvo (foco da janela): " + t[1] + " pid=" + pid);
            return;
        }
    }

    private void chooseLayer(List<String> layers) {
        if (targetName.length() == 0) return;
        String best = "";
        for (String l : layers) {
            if (!l.contains(targetName) || l.startsWith("Background")) continue;
            if (l.startsWith("SurfaceView")) {
                best = l;
                break;
            }
            if (best.isEmpty()) best = l;
        }
        if (!best.isEmpty() && !best.contains("'") && !best.trim().equals(fpsLayer)) {
            fpsLayer = best.trim();
            note("layer do FPS: " + fpsLayer);
        }
    }

    private void computeFps(List<String> lat) {
        if (lat.size() < 2) {
            if (fpsLayer.length() > 0) {
                note("latency vazio: layer sumiu, vou procurar de novo");
                fpsLayer = "";
            }
            fps = -1;
            return;
        }
        long tmax = -1;
        List<Long> ts = new ArrayList<Long>();
        for (int i = 1; i < lat.size(); i++) {
            String[] p = lat.get(i).trim().split("\\s+");
            if (p.length < 3) continue;
            long t = num(p[1]);
            if (t <= 0 || t == Long.MAX_VALUE) continue;
            ts.add(t);
            if (t > tmax) tmax = t;
        }
        if (ts.isEmpty()) {
            fps = -1;
            return;
        }
        int n = 0;
        for (long t : ts) {
            if (t > tmax - 1000000000L) n++;
        }
        if (tmax == prevFrameT) n = 0; // nenhum quadro novo desde o ciclo anterior
        prevFrameT = tmax;
        fps = n;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
