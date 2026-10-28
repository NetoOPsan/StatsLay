package com.netoop.g9overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transforma as linhas do overlay em "segmentos" curtos para o layout em BARRA horizontal (estilo GameHub).
 * Sem dependência do Android.
 */
public final class Segs {

    /** dot: -1 sem bolinha, senão o nível da bolinha (4 verde, 1 amarelo, 2 vermelho). */
    public static final class Seg {
        public final String label, value;
        public final int level, dot;

        Seg(String label, String value, int level, int dot) {
            this.label = label;
            this.value = value;
            this.level = level;
            this.dot = dot;
        }
    }

    private Segs() {
    }

    public static List<Seg> build(List<Collector.Line> lines, boolean ghz, GpuHealth.Result gh) {
        List<Seg> out = new ArrayList<Seg>();
        int sMax = -1, gMax = -1;
        int gpuDot = (gh == null || gh.state == GpuHealth.State.NO_DATA) ? -1 : (gh.level == 0 ? 4 : gh.level);
        for (Collector.Line l : lines) {
            String t = l.text.trim();
            int sp = t.indexOf(' ');
            String label = sp < 0 ? t : t.substring(0, sp);
            String rest = sp < 0 ? "" : t.substring(sp).trim().replaceAll("\\s+", " ");
            if (label.equals("S") || label.equals("G")) {
                int mx = maxInt(rest);
                if (label.equals("S")) sMax = mx;
                else {
                    gMax = mx;
                    String v = clk(sMax, gMax, ghz);
                    if (v != null) out.add(new Seg("CLK", v, 0, -1));
                }
                continue;
            }
            if (label.equals("RAM")) {
                // "2.9G/3.7G livre 744M" -> "2.9G·744M"
                String used = rest.split("/")[0];
                int i = rest.indexOf("livre ");
                out.add(new Seg("RAM", i >= 0 ? used + "·" + rest.substring(i + 6) : used, l.level, -1));
            } else if (label.equals("ZRAM")) {
                if (rest.startsWith("off")) {
                    out.add(new Seg("ZRAM", "off", l.level, -1));
                } else {
                    // "3.6G/6.0G RAM real 282M 13.5:1" -> "3.6G·282M"
                    String used = rest.split("/")[0];
                    int i = rest.indexOf("real ");
                    String real = i >= 0 ? rest.substring(i + 5).split(" ")[0] : null;
                    out.add(new Seg("ZRAM", real != null ? used + "·" + real : used, l.level, -1));
                }
            } else if (label.equals("SWAP")) {
                out.add(new Seg("SWAP", rest.startsWith("off") ? "off" : rest.split("/")[0], l.level, -1));
            } else if (label.equals("SWPIO")) {
                // "ZRAM in 4.9 out 0.8" ou "in 4.9 out 0.8 MB/s"
                String[] t2 = rest.replace("ZRAM ", "").replace(" MB/s", "").split(" ");
                if (t2.length >= 4 && t2[0].equals("in")) out.add(new Seg("I/O", "↓" + t2[1] + " ↑" + t2[3], l.level, -1));
            } else if (label.equals("DISCO")) {
                String[] t2 = rest.replace(" MB/s", "").split(" ");
                if (t2.length >= 4 && (num(t2[1]) >= 0.05 || num(t2[3]) >= 0.05)) {
                    out.add(new Seg("DISCO", "↓" + t2[1] + " ↑" + t2[3], l.level, -1));
                }
            } else if (label.equals("PSI")) {
                String[] t2 = rest.split(" ");
                if (t2.length >= 4 && t2[0].equals("some")) out.add(new Seg("PSI", t2[1] + "/" + t2[3], l.level, -1));
            } else if (label.equals("CPU")) {
                // "91% S 84% G 98%" -> "91%"
                out.add(new Seg("CPU", rest.split(" ")[0], l.level, -1));
            } else if (label.equals("GPU")) {
                if (rest.startsWith("●")) {
                    if (gh != null && (gh.state == GpuHealth.State.LIMITED || gh.state == GpuHealth.State.STUCK
                            || gh.state == GpuHealth.State.SLOW_GOV)) {
                        out.add(new Seg("GPU!", gh.text, gh.level, -1));
                    }
                } else {
                    out.add(new Seg("GPU", rest.replace(" MHz", "MHz"), l.level, gpuDot));
                }
            } else if (label.equals("LIM")) {
                if (!rest.startsWith("nenhum")) out.add(new Seg("LIM", rest, l.level, -1));
            } else if (label.equals("TEMP")) {
                // "CPU 76 GPU 70 PCB 52 BAT 45" -> "C76 G70 P52 B45"
                String v = rest.replace("CPU ", "C").replace("GPU ", "G").replace("PCB ", "P").replace("BAT ", "B");
                out.add(new Seg("TEMP", v, l.level, -1));
            } else if (label.equals("BAT")) {
                out.add(new Seg("BAT", rest.replace(" mA", "mA").replaceAll(" (carregando|descarga|cheia)$", ""), l.level, -1));
            } else if (label.equals("APP")) {
                if (!rest.startsWith("--")) {
                    String[] t2 = rest.split(" ");
                    out.add(new Seg("APP", t2.length >= 2 ? t2[0] + " " + t2[1] : t2[0], l.level, -1));
                }
            } else if (label.equals("FPS")) {
                out.add(new Seg("FPS", rest, l.level, -1));
            } else if (label.equals("OVL")) {
                out.add(new Seg("OVL", rest.replace(" MB ", "MB ").replace(" cpu", ""), l.level, -1));
            }
        }
        return out;
    }

    private static String clk(int s, int g, boolean ghz) {
        if (s < 0 && g < 0) return null;
        return fmt(s, ghz) + "/" + fmt(g, ghz) + (ghz ? "G" : "");
    }

    private static String fmt(int mhz, boolean ghz) {
        if (mhz < 0) return "-";
        return ghz ? String.format(Locale.US, "%.1f", mhz / 1000.0) : String.valueOf(mhz);
    }

    private static int maxInt(String row) {
        int mx = -1;
        for (String t : row.split(" ")) {
            try {
                mx = Math.max(mx, Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
            }
        }
        return mx;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
