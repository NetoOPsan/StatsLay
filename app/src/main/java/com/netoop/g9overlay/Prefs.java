package com.netoop.g9overlay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Configurações salvas: métricas, aparência e comportamento. */
public final class Prefs {

    /** Chaves das métricas (mesma ordem de LABELS e DESCS). */
    public static final String[] KEYS = {
            "ram", "zram", "swap", "swapio", "psi", "cpu", "cpufreq",
            "gpu", "gpustatus", "temp", "bat", "app", "fps", "limit", "self"
    };
    public static final String[] LABELS = {
            "RAM", "ZRAM", "Swap em disco", "Swap I/O", "Pressão de memória", "CPU %", "CPU clocks",
            "GPU", "Estado da GPU", "Temperaturas", "Bateria", "App em foco", "FPS (experimental)", "Limites / throttling", "Custo do overlay"
    };
    public static final String[] DESCS = {
            "Usada, total e livre", "Usado, RAM real e taxa de compressão", "Uso do swap em arquivo/disco",
            "MB/s trocados com o swap", "Tempo parado esperando memória (PSI)", "Total e por cluster (S/G)",
            "MHz de cada núcleo", "Clock e ocupação", "Normal, limitada ou presa no mínimo", "CPU, GPU, placa e bateria", "Nível, corrente e estado",
            "RAM e swap do app na tela", "Quadros por segundo do app na tela", "Aviso se clock/GPU estão limitados",
            "RAM e CPU do próprio overlay"
    };

    /** Grupos para a tela de configurações: nome do grupo seguido das chaves. */
    public static final String[][] GROUPS = {
            {"Memória", "ram", "zram", "swap", "swapio", "psi"},
            {"Processador", "cpu", "cpufreq", "limit"},
            {"Gráficos", "gpu", "gpustatus", "fps"},
            {"Sistema", "temp", "bat", "app", "self"}
    };

    public static final String[] THEME_NAMES = {"Escuro", "Azul", "Verde", "Roxo"};
    // fundo (r,g,b) e cor de destaque (r,g,b) de cada tema
    public static final int[][] THEME_BG = {{0, 0, 0}, {8, 20, 48}, {5, 28, 16}, {28, 12, 46}};
    public static final int[][] THEME_ACCENT = {{34, 211, 238}, {125, 170, 255}, {94, 234, 160}, {196, 150, 255}};

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("g9overlay", Context.MODE_PRIVATE);
    }

    public static String label(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return LABELS[i];
        return key;
    }

    public static String desc(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return DESCS[i];
        return "";
    }

    private static boolean defaultFor(String k) {
        // Ligadas por padrão: tudo, menos bateria, FPS e custo do overlay.
        return !(k.equals("bat") || k.equals("fps") || k.equals("self"));
    }

    public boolean on(String k) {
        return sp.getBoolean("m_" + k, defaultFor(k));
    }

    public void set(String k, boolean v) {
        sp.edit().putBoolean("m_" + k, v).apply();
    }

    public Set<String> enabledSet() {
        Set<String> s = new HashSet<String>();
        for (String k : KEYS) if (on(k)) s.add(k);
        return s;
    }

    // ---- genéricos
    public int getInt(String k, int def) {
        return sp.getInt(k, def);
    }

    public void setInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public boolean getBool(String k, boolean def) {
        return sp.getBoolean(k, def);
    }

    public void setBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    // ---- aparência
    public int bgPercent() {
        return clamp(sp.getInt("bg_pct", 60), 0, 100);
    }

    public int textSp() {
        return clamp(sp.getInt("text_sp", 10), 7, 18);
    }

    public void setTextSp(int v) {
        setInt("text_sp", clamp(v, 7, 18));
    }

    public int radiusDp() {
        return clamp(sp.getInt("radius_dp", 10), 0, 24);
    }

    public int theme() {
        return clamp(sp.getInt("theme", 0), 0, THEME_NAMES.length - 1);
    }

    public boolean colorize() {
        return sp.getBoolean("colorize", true);
    }

    public boolean bold() {
        return sp.getBoolean("bold", false);
    }

    // ---- layout e estilo (v1.7)
    /** 0 = lista vertical; 1 = barra horizontal (estilo GameHub). */
    public int layout() {
        return clamp(sp.getInt("layout", 0), 0, 1);
    }

    /** 0 = contínua com divisores; 1 = "chips" arredondados. */
    public int barStyle() {
        return clamp(sp.getInt("bar_style", 1), 0, 1);
    }

    /** 0 = esquerda, 1 = centro, 2 = direita (no topo da tela). */
    public int anchor() {
        return clamp(sp.getInt("anchor", 0), 0, 2);
    }

    public int barWidthPct() {
        return clamp(sp.getInt("bar_width", 96), 40, 100);
    }

    public int padDp() {
        return clamp(sp.getInt("pad_dp", 6), 0, 14);
    }

    public int textAlphaPct() {
        return clamp(sp.getInt("text_alpha", 100), 30, 100);
    }

    /** 0 = monoespaçada, 1 = sans, 2 = condensada. */
    public int font() {
        return clamp(sp.getInt("font", 0), 0, 2);
    }

    public boolean shadow() {
        return sp.getBoolean("shadow", true);
    }

    public boolean border() {
        return sp.getBoolean("border", true);
    }

    public boolean ghz() {
        return sp.getBoolean("ghz", false);
    }

    public boolean accentCustom() {
        return sp.getBoolean("accent_on", false);
    }

    public int accentHue() {
        return clamp(sp.getInt("accent_hue", 190), 0, 360);
    }

    /** Cor de destaque: a escolhida pelo matiz, ou a do tema. */
    public int accentColor() {
        if (accentCustom()) return android.graphics.Color.HSVToColor(new float[]{accentHue(), 0.70f, 1.0f});
        int[] ac = THEME_ACCENT[theme()];
        return android.graphics.Color.rgb(ac[0], ac[1], ac[2]);
    }

    // ---- guarda da GPU
    public boolean guardOn() {
        return sp.getBoolean("guard_on", true);
    }

    /** Acima dessa temperatura da GPU o guarda NÃO age (a proteção do kernel precisa valer). */
    public int guardTemp() {
        return clamp(sp.getInt("guard_temp", 88), 70, 95);
    }

    public boolean gpuPin() {
        return sp.getBoolean("gpu_pin", false);
    }

    /** Perfis prontos de layout + métricas. 0 completo, 1 GameHub, 2 mínimo, 3 diagnóstico. */
    public void applyPreset(int preset) {
        SharedPreferences.Editor e = sp.edit();
        for (String k : KEYS) e.putBoolean("m_" + k, false);
        if (preset == 0) {
            for (String k : KEYS) e.putBoolean("m_" + k, defaultFor(k));
            e.putInt("layout", 0).putInt("anchor", 0).putInt("pos_x", 8).putInt("pos_y", 8);
        } else if (preset == 1) {
            for (String k : new String[]{"fps", "cpu", "cpufreq", "gpu", "gpustatus", "ram", "temp"}) e.putBoolean("m_" + k, true);
            e.putInt("layout", 1).putInt("bar_style", 1).putInt("anchor", 1).putInt("pos_x", 0).putInt("pos_y", 4)
                    .putInt("bar_width", 96).putInt("text_sp", 10).putInt("bg_pct", 55).putInt("radius_dp", 14);
        } else if (preset == 2) {
            for (String k : new String[]{"fps", "gpu", "gpustatus", "cpu", "ram"}) e.putBoolean("m_" + k, true);
            e.putInt("layout", 1).putInt("bar_style", 1).putInt("anchor", 1).putInt("pos_x", 0).putInt("pos_y", 4)
                    .putInt("bar_width", 96).putInt("text_sp", 10).putInt("bg_pct", 45).putInt("radius_dp", 14);
        } else {
            for (String k : KEYS) e.putBoolean("m_" + k, true);
            e.putInt("layout", 0).putInt("anchor", 0).putInt("pos_x", 8).putInt("pos_y", 8);
        }
        e.apply();
    }

    // ---- comportamento
    public int intervalMs() {
        return sp.getInt("interval_ms", 1000);
    }

    public boolean lockPos() {
        return sp.getBoolean("lock_pos", false);
    }

    public boolean clickThrough() {
        return sp.getBoolean("click_through", false);
    }

    public boolean autostart() {
        return sp.getBoolean("autostart", false);
    }

    // ---- diagnóstico automático (grava no log os mesmos valores que eu pedia pelo Termux)
    public boolean diagOn() {
        return sp.getBoolean("diag_on", true);
    }

    public int diagIntervalSec() {
        return clamp(sp.getInt("diag_sec", 15), 5, 300);
    }

    public int posX() {
        return sp.getInt("pos_x", 8);
    }

    public int posY() {
        return sp.getInt("pos_y", 8);
    }

    public void setPos(int x, int y) {
        sp.edit().putInt("pos_x", x).putInt("pos_y", y).apply();
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.registerOnSharedPreferenceChangeListener(l);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.unregisterOnSharedPreferenceChangeListener(l);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
