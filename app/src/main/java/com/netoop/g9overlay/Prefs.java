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

    // ── Temas ─────────────────────────────────────────────────────────────────
    public static final String[] THEME_NAMES = {"Cósmico", "Oceano", "Floresta", "Aurora", "Entardecer", "Carvão"};
    // fundo (r,g,b): mais escuro e saturado que v1.7
    public static final int[][] THEME_BG = {
            {8,  10, 18},   // Cósmico  (azul profundo)
            {6,  18, 32},   // Oceano
            {5,  20, 12},   // Floresta
            {18, 8,  26},   // Aurora   (roxo)
            {26, 14, 6},    // Entardecer (laranja escuro)
            {12, 12, 12}    // Carvão   (neutro)
    };
    // cor de destaque (r,g,b)
    public static final int[][] THEME_ACCENT = {
            {99,  179, 237},  // azul-céu
            {56,  189, 248},  // ciano claro
            {74,  222, 128},  // verde esmeralda
            {192, 132, 252},  // violeta
            {251, 146, 60},   // laranja
            {148, 163, 184}   // cinza-gelo
    };

    // ── Estilos de overlay ────────────────────────────────────────────────────
    /** Estilos visuais do overlay (além de lista/barra já existentes). */
    public static final String[] OVERLAY_STYLE_NAMES = {"Padrão", "Minimalista", "Glassmorphism", "Neon"};

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

    // ── Genéricos ─────────────────────────────────────────────────────────────
    public int getInt(String k, int def) { return sp.getInt(k, def); }
    public void setInt(String k, int v)  { sp.edit().putInt(k, v).apply(); }
    public boolean getBool(String k, boolean def) { return sp.getBoolean(k, def); }
    public void setBool(String k, boolean v)      { sp.edit().putBoolean(k, v).apply(); }

    // ── Aparência ─────────────────────────────────────────────────────────────
    public int bgPercent()  { return clamp(sp.getInt("bg_pct",    60),   0,  100); }
    public int textSp()     { return clamp(sp.getInt("text_sp",   10),   7,   18); }
    public int radiusDp()   { return clamp(sp.getInt("radius_dp", 10),   0,   28); }
    public int theme()      { return clamp(sp.getInt("theme",      0),   0, THEME_NAMES.length - 1); }
    public boolean colorize()    { return sp.getBoolean("colorize", true); }
    public boolean bold()        { return sp.getBoolean("bold", false); }
    public boolean shadow()      { return sp.getBoolean("shadow", true); }
    public boolean border()      { return sp.getBoolean("border", true); }
    public boolean ghz()         { return sp.getBoolean("ghz", false); }
    public boolean accentCustom(){ return sp.getBoolean("accent_on", false); }

    public void setTextSp(int v) { setInt("text_sp", clamp(v, 7, 18)); }

    public int accentHue() { return clamp(sp.getInt("accent_hue", 210), 0, 360); }

    /** Cor de destaque: matiz personalizado ou a do tema. */
    public int accentColor() {
        if (accentCustom()) return android.graphics.Color.HSVToColor(new float[]{accentHue(), 0.75f, 0.95f});
        int[] ac = THEME_ACCENT[theme()];
        return android.graphics.Color.rgb(ac[0], ac[1], ac[2]);
    }

    // ── Layout e estilo ───────────────────────────────────────────────────────
    /** 0 = lista vertical; 1 = barra horizontal. */
    public int layout()      { return clamp(sp.getInt("layout",    0), 0, 1); }
    /** 0 = contínua com divisores; 1 = chips arredondados. */
    public int barStyle()    { return clamp(sp.getInt("bar_style", 1), 0, 1); }
    /** 0 = esquerda, 1 = centro, 2 = direita. */
    public int anchor()      { return clamp(sp.getInt("anchor",    0), 0, 2); }
    public int barWidthPct() { return clamp(sp.getInt("bar_width", 96), 40, 100); }
    public int padDp()       { return clamp(sp.getInt("pad_dp",    6),  0, 16); }
    public int textAlphaPct(){ return clamp(sp.getInt("text_alpha", 100), 30, 100); }
    /** 0 = mono, 1 = sans, 2 = condensada. */
    public int font()        { return clamp(sp.getInt("font",  0), 0, 2); }

    // ── Novas opções de personalização ───────────────────────────────────────

    /** Estilo visual do overlay: 0 padrão, 1 minimalista, 2 glassmorphism, 3 neon. */
    public int overlayStyle() { return clamp(sp.getInt("overlay_style", 0), 0, 3); }

    /** Intensidade do brilho neon (só no estilo Neon). 0–100. */
    public int neonGlow()     { return clamp(sp.getInt("neon_glow", 60), 0, 100); }

    /** Tamanho do rótulo vs valor no overlay: 0 igual, 1 rótulo menor, 2 rótulo oculto. */
    public int labelSize()    { return clamp(sp.getInt("label_size", 1), 0, 2); }

    /** Exibir ícone emoji antes de cada métrica no overlay. */
    public boolean showIcons() { return sp.getBoolean("show_icons", true); }

    /** Mostrar barra de progresso mini abaixo do valor de CPU/GPU (só layout lista). */
    public boolean showMiniBar() { return sp.getBoolean("show_mini_bar", false); }

    /** Tempo (ms) de animação ao atualizar valores: 0 = sem animação. */
    public int updateAnim()   { return clamp(sp.getInt("update_anim_ms", 120), 0, 400); }

    /** Tocar vibração curta ao abrir o painel do overlay. */
    public boolean hapticPanel() { return sp.getBoolean("haptic_panel", true); }

    /** Temperatura mínima para alertar (°C). */
    public int warnTempC()    { return clamp(sp.getInt("warn_temp_c", 70), 40, 90); }

    /** Cor de fundo do painel rápido do overlay: 0 = tema, 1 = sempre escuro. */
    public int panelBg()      { return clamp(sp.getInt("panel_bg", 0), 0, 1); }

    // ── Guarda da GPU ─────────────────────────────────────────────────────────
    public boolean guardOn()  { return sp.getBoolean("guard_on", true); }
    public int guardTemp()    { return clamp(sp.getInt("guard_temp", 88), 70, 95); }
    public boolean gpuPin()   { return sp.getBoolean("gpu_pin", false); }

    // ── Perfis prontos ────────────────────────────────────────────────────────
    /** 0 completo, 1 GameHub, 2 mínimo, 3 diagnóstico, 4 neon gaming. */
    public void applyPreset(int preset) {
        SharedPreferences.Editor e = sp.edit();
        for (String k : KEYS) e.putBoolean("m_" + k, false);
        switch (preset) {
            case 0: // Completo
                for (String k : KEYS) e.putBoolean("m_" + k, defaultFor(k));
                e.putInt("layout", 0).putInt("anchor", 0).putInt("pos_x", 8).putInt("pos_y", 8)
                        .putInt("overlay_style", 0).putInt("label_size", 1).putBoolean("show_icons", true);
                break;
            case 1: // GameHub
                for (String k : new String[]{"fps", "cpu", "cpufreq", "gpu", "gpustatus", "ram", "temp"})
                    e.putBoolean("m_" + k, true);
                e.putInt("layout", 1).putInt("bar_style", 1).putInt("anchor", 1)
                        .putInt("pos_x", 0).putInt("pos_y", 4)
                        .putInt("bar_width", 96).putInt("text_sp", 10)
                        .putInt("bg_pct", 55).putInt("radius_dp", 14)
                        .putInt("overlay_style", 1).putInt("label_size", 1);
                break;
            case 2: // Mínimo
                for (String k : new String[]{"fps", "gpu", "cpu", "ram"}) e.putBoolean("m_" + k, true);
                e.putInt("layout", 1).putInt("bar_style", 1).putInt("anchor", 1)
                        .putInt("pos_x", 0).putInt("pos_y", 4)
                        .putInt("bar_width", 80).putInt("text_sp", 9)
                        .putInt("bg_pct", 40).putInt("radius_dp", 16)
                        .putInt("overlay_style", 1).putInt("label_size", 2)
                        .putBoolean("show_icons", false);
                break;
            case 3: // Diagnóstico
                for (String k : KEYS) e.putBoolean("m_" + k, true);
                e.putInt("layout", 0).putInt("anchor", 0).putInt("pos_x", 8).putInt("pos_y", 8)
                        .putInt("overlay_style", 0).putInt("label_size", 0).putBoolean("show_icons", false);
                break;
            case 4: // Neon Gaming
                for (String k : new String[]{"fps", "cpu", "gpu", "gpustatus", "ram", "temp"})
                    e.putBoolean("m_" + k, true);
                e.putInt("layout", 1).putInt("bar_style", 1).putInt("anchor", 1)
                        .putInt("pos_x", 0).putInt("pos_y", 4)
                        .putInt("bar_width", 96).putInt("text_sp", 11)
                        .putInt("bg_pct", 70).putInt("radius_dp", 12)
                        .putInt("overlay_style", 3).putInt("neon_glow", 70)
                        .putInt("label_size", 1).putBoolean("show_icons", true)
                        .putInt("theme", 0).putBoolean("accent_on", false);
                break;
        }
        e.apply();
    }

    // ── Comportamento ─────────────────────────────────────────────────────────
    public int intervalMs()  { return sp.getInt("interval_ms", 1000); }
    public boolean lockPos()      { return sp.getBoolean("lock_pos",   false); }
    public boolean clickThrough() { return sp.getBoolean("click_through", false); }
    public boolean autostart()    { return sp.getBoolean("autostart", false); }

    // ── Diagnóstico ───────────────────────────────────────────────────────────
    public boolean diagOn()       { return sp.getBoolean("diag_on", true); }
    public int diagIntervalSec()  { return clamp(sp.getInt("diag_sec", 15), 5, 300); }

    // ── Posição do overlay ────────────────────────────────────────────────────
    public int posX() { return sp.getInt("pos_x", 8); }
    public int posY() { return sp.getInt("pos_y", 8); }
    public void setPos(int x, int y) { sp.edit().putInt("pos_x", x).putInt("pos_y", y).apply(); }

    // ── Listeners ─────────────────────────────────────────────────────────────
    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.registerOnSharedPreferenceChangeListener(l);
    }
    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.unregisterOnSharedPreferenceChangeListener(l);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
