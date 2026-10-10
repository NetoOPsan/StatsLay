package com.netoop.g9overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pequenas peças de interface (tema escuro, cartões arredondados) montadas em código. */
public final class Ui {

    public static final int BG = Color.rgb(14, 17, 22);
    public static final int CARD = Color.rgb(23, 27, 34);
    public static final int TEXT = Color.rgb(232, 234, 237);
    public static final int SUB = Color.rgb(154, 163, 175);
    public static final int ACCENT = Color.rgb(34, 211, 238);
    public static final int GOOD = Color.rgb(52, 211, 153);
    public static final int WARN = Color.rgb(251, 191, 36);
    public static final int BAD = Color.rgb(248, 113, 113);

    private Ui() {
    }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, 16, c));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 12));
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView cardTitle(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, ACCENT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    /** primary = botão cheio na cor de destaque; senão, botão discreto com contorno. */
    public static Button button(Context c, String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setStateListAnimator(null);
        if (primary) {
            b.setBackground(round(ACCENT, 12, c));
            b.setTextColor(Color.rgb(8, 20, 28));
        } else {
            GradientDrawable g = round(Color.rgb(32, 38, 48), 12, c);
            g.setStroke(dp(c, 1), Color.argb(90, 255, 255, 255));
            b.setBackground(g);
            b.setTextColor(TEXT);
        }
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 4), 0, dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /** Linha de status: bolinha colorida + texto. */
    public static TextView chip(Context c, String s, int dotColor) {
        TextView t = text(c, "●  " + s, 13, TEXT, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        t.setBackground(round(Color.rgb(32, 38, 48), 20, c));
        android.text.SpannableString sp = new android.text.SpannableString(t.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(dotColor), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 8), 0);
        t.setLayoutParams(lp);
        return t;
    }
}
