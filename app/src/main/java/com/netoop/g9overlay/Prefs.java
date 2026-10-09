package com.netoop.g9overlay;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Configurações salvas: quais métricas aparecem, tamanho do texto, intervalo e posição. */
public final class Prefs {

    /** Chaves internas de cada métrica (mesma ordem de LABELS). */
    public static final String[] KEYS = {
            "ram", "zram", "swap", "swapio", "psi", "cpu", "cpufreq",
            "gpu", "temp", "bat", "app", "fps", "limit", "self"
    };
    public static final String[] LABELS = {
            "RAM", "ZRAM", "Swap em disco", "Swap I/O", "Pressão (PSI)", "CPU %", "CPU clocks",
            "GPU", "Temperaturas", "Bateria", "App em foco", "FPS (exp.)", "Limites", "Uso do overlay"
    };

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("g9overlay", Context.MODE_PRIVATE);
    }

    private static boolean defaultFor(String k) {
        // Ligadas por padrão: tudo, menos bateria, FPS e uso do próprio overlay.
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
        for (String k : KEYS) {
            if (on(k)) s.add(k);
        }
        return s;
    }

    public int textSp() {
        return sp.getInt("text_sp", 10);
    }

    public void setTextSp(int v) {
        sp.edit().putInt("text_sp", Math.max(7, Math.min(18, v))).apply();
    }

    public int intervalMs() {
        return sp.getInt("interval_ms", 1000);
    }

    public void setIntervalMs(int v) {
        sp.edit().putInt("interval_ms", v).apply();
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
}
