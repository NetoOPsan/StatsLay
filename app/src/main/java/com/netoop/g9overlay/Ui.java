package com.netoop.g9overlay;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sistema de design do G9 Overlay — tokens de cor, fábrica de componentes e helpers.
 * Todos os valores vêm daqui: trocar o tema exige mudar só esta classe.
 */
public final class Ui {

    // ── Tokens de cor base ────────────────────────────────────────────────────
    public static final int BG       = Color.rgb(10, 12, 18);   // fundo da tela
    public static final int SURFACE  = Color.rgb(18, 21, 30);   // cartão
    public static final int SURFACE2 = Color.rgb(24, 28, 40);   // cartão elevado / input
    public static final int BORDER   = Color.argb(40, 255, 255, 255);  // divider / contorno sutil
    public static final int TEXT     = Color.rgb(228, 232, 240);
    public static final int SUB      = Color.rgb(120, 130, 150);
    public static final int ACCENT   = Color.rgb(99, 179, 237);  // azul-céu (padrão)
    public static final int GOOD     = Color.rgb(72, 199, 142);
    public static final int WARN     = Color.rgb(246, 173, 85);
    public static final int BAD      = Color.rgb(252, 100, 100);

    // Raio padrão dos cartões
    private static final int CARD_RADIUS = 18;
    private static final int BTN_RADIUS  = 14;
    private static final int CHIP_RADIUS = 24;

    private Ui() {}

    // ── Conversão dp/sp ───────────────────────────────────────────────────────

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static float dpF(Context c, float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ── Drawables ─────────────────────────────────────────────────────────────

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** Borda fina + fundo sólido. */
    public static GradientDrawable roundStroked(int fill, int stroke, int radiusDp, int strokeDp, Context c) {
        GradientDrawable g = round(fill, radiusDp, c);
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    // ── Cartões ───────────────────────────────────────────────────────────────

    /**
     * Cartão padrão: fundo SURFACE com borda sutil e raio arredondado.
     * marginBottom = 14 dp por padrão.
     */
    public static LinearLayout card(Context c) {
        return card(c, 14);
    }

    public static LinearLayout card(Context c, int marginBottomDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = roundStroked(SURFACE, BORDER, CARD_RADIUS, 1, c);
        l.setBackground(bg);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, marginBottomDp));
        l.setLayoutParams(lp);
        return l;
    }

    /** Cartão com gradiente sutil para destacar seções importantes (ex.: status). */
    public static LinearLayout heroCard(Context c, int accentColor) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int r = dp(c, CARD_RADIUS);
        int ar = Color.argb(25, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ar, SURFACE});
        bg.setCornerRadius(r);
        bg.setStroke(dp(c, 1), Color.argb(80, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
        l.setBackground(bg);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 14));
        l.setLayoutParams(lp);
        return l;
    }

    // ── Textos ────────────────────────────────────────────────────────────────

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Título de seção: CAPSLOCK + espaçamento + cor de destaque. */
    public static TextView sectionTitle(Context c, String s, int accentColor) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(accentColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(c, 10));
        return t;
    }

    /** Atalho para sectionTitle com ACCENT padrão. */
    public static TextView cardTitle(Context c, String s) {
        return sectionTitle(c, s, ACCENT);
    }

    /** Rótulo de grupo de opções — menos destaque que sectionTitle. */
    public static TextView groupLabel(Context c, String s) {
        TextView t = text(c, s, 13, SUB, false);
        t.setPadding(0, dp(c, 10), 0, dp(c, 4));
        return t;
    }

    // ── Botões ────────────────────────────────────────────────────────────────

    /** Botão primário (preenchido com cor de destaque) ou secundário (contorno). */
    public static Button button(Context c, String label, boolean primary, View.OnClickListener l) {
        return button(c, label, primary, ACCENT, l);
    }

    public static Button button(Context c, String label, boolean primary, int accentColor, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setStateListAnimator(null);
        if (primary) {
            b.setBackground(round(accentColor, BTN_RADIUS, c));
            // texto escuro sobre cor de destaque para contraste
            float[] hsv = new float[3];
            Color.colorToHSV(accentColor, hsv);
            b.setTextColor(hsv[2] > 0.6f ? Color.rgb(8, 15, 25) : Color.WHITE);
        } else {
            b.setBackground(roundStroked(SURFACE2, BORDER, BTN_RADIUS, 1, c));
            b.setTextColor(TEXT);
        }
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 4), 0, dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /** Botão "perigoso" — vermelho para ações destrutivas. */
    public static Button dangerButton(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, false, l);
        b.setBackground(roundStroked(Color.argb(40, 255, 60, 60), BAD, BTN_RADIUS, 1, c));
        b.setTextColor(BAD);
        return b;
    }

    // ── Chips de status ───────────────────────────────────────────────────────

    /** Bolinha colorida + texto — indicador de estado compacto. */
    public static TextView chip(Context c, String s, int dotColor) {
        TextView t = new TextView(c);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int ph = dp(c, 12), pv = dp(c, 6);
        t.setPadding(ph, pv, ph, pv);
        int bgAlpha = Color.argb(40, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor));
        GradientDrawable bg = roundStroked(bgAlpha, Color.argb(80, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor)), CHIP_RADIUS, 1, c);
        t.setBackground(bg);

        android.text.SpannableString sp = new android.text.SpannableString("● " + s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(dotColor), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new android.text.style.ForegroundColorSpan(TEXT), 2, sp.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sp);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 8), dp(c, 6));
        t.setLayoutParams(lp);
        return t;
    }

    // ── Divider ───────────────────────────────────────────────────────────────

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1));
        lp.setMargins(0, dp(c, 10), 0, dp(c, 10));
        v.setLayoutParams(lp);
        return v;
    }

    // ── Badge de valor ────────────────────────────────────────────────────────

    /** Pequeno badge colorido para exibir um valor de destaque (ex.: "Root OK"). */
    public static TextView badge(Context c, String text, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int ph = dp(c, 8), pv = dp(c, 3);
        t.setPadding(ph, pv, ph, pv);
        int bg = Color.argb(35, Color.red(color), Color.green(color), Color.blue(color));
        t.setBackground(round(bg, CHIP_RADIUS, c));
        return t;
    }

    // ── Animação de pulso ─────────────────────────────────────────────────────

    /** Pisca a view de forma suave (útil para indicadores de estado). */
    public static void pulse(View v) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.4f, 1f);
        anim.setDuration(800);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.start();
    }
}
