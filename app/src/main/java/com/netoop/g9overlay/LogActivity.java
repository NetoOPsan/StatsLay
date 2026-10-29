package com.netoop.g9overlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Tela de log — redesenhada para v1.8. */
public class LogActivity extends Activity {

    private TextView tv;
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ── Cabeçalho ─────────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 16);
        header.setPadding(p, Ui.dp(this, 20), p, p);

        // Linha: título + botão voltar
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView appName = Ui.text(this, "Log do app", 22, Ui.TEXT, true);
        TextView pathTv  = Ui.text(this, AppLog.path(), 10, Ui.SUB, false);
        pathTv.setPadding(0, Ui.dp(this, 2), 0, 0);
        titles.addView(appName);
        titles.addView(pathTv);
        titleRow.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button backBtn = new android.widget.Button(this);
        backBtn.setText("← Voltar");
        backBtn.setAllCaps(false);
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        backBtn.setMinWidth(0); backBtn.setMinimumWidth(0);
        backBtn.setStateListAnimator(null);
        backBtn.setBackground(Ui.roundStroked(Ui.SURFACE2, Ui.BORDER, 20, 1, this));
        backBtn.setTextColor(Ui.SUB);
        int ph = Ui.dp(this, 14), pv = Ui.dp(this, 8);
        backBtn.setPadding(ph, pv, ph, pv);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        backBtn.setLayoutParams(blp);
        titleRow.addView(backBtn);
        header.addView(titleRow);

        // Linha de botões de ação — 2 linhas de 3
        LinearLayout actions1 = makeActionRow();
        actions1.addView(small("↻ Atualizar", true, new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        }));
        actions1.addView(small("⎘ Copiar", false, new View.OnClickListener() {
            @Override public void onClick(View v) { copy(); }
        }));
        actions1.addView(small("⬆ Compartilhar", false, new View.OnClickListener() {
            @Override public void onClick(View v) { share(); }
        }));
        LinearLayout actions2 = makeActionRow();
        actions2.addView(small("↓ Exportar (root)", false, new View.OnClickListener() {
            @Override public void onClick(View v) { exportRoot(); }
        }));
        actions2.addView(small("🗑 Limpar", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                AppLog.clear();
                AppLog.log("log", "log limpo");
                refresh();
                Toast.makeText(LogActivity.this, "Log limpo", Toast.LENGTH_SHORT).show();
            }
        }));
        header.addView(actions1);
        header.addView(actions2);
        root.addView(header);

        // Linha divisória
        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        // ── Área do log ───────────────────────────────────────────────────────
        sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(10, 12, 16));
        tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tv.setTextColor(Color.rgb(180, 200, 220));
        tv.setTextIsSelectable(true);
        int lp2 = Ui.dp(this, 12);
        tv.setPadding(lp2, lp2, lp2, lp2);
        tv.setLineSpacing(Ui.dp(this, 1), 1.0f);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tv);
        sv.addView(hs);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, svLp);

        setContentView(root);
        refresh();
    }

    private LinearLayout makeActionRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        r.setLayoutParams(lp);
        return r;
    }

    private android.widget.Button small(String label, boolean primary, View.OnClickListener l) {
        android.widget.Button bt = Ui.button(this, label, primary, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        bt.setLayoutParams(lp);
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bt.setMinHeight(Ui.dp(this, 40));
        bt.setMinimumHeight(Ui.dp(this, 40));
        return bt;
    }

    private void refresh() {
        final String t = AppLog.tail(60000);
        tv.setText(t.isEmpty() ? "(log vazio)" : t);
        sv.post(new Runnable() {
            @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("g9overlay-log", AppLog.tail(60000)));
        Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, AppLog.tail(60000));
        startActivity(Intent.createChooser(i, "Compartilhar log"));
    }

    private void exportRoot() {
        final String src = AppLog.path();
        new Thread(new Runnable() {
            @Override public void run() {
                String res;
                try {
                    Process pr = new ProcessBuilder("su", "-c",
                            "cp '" + src + "' /sdcard/Download/g9overlay-log.txt "
                                    + "&& chmod 666 /sdcard/Download/g9overlay-log.txt && echo OK")
                            .redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    pr.waitFor();
                    res = sb.toString().contains("OK")
                            ? "Exportado: Download/g9overlay-log.txt"
                            : "Falhou: " + sb;
                } catch (Exception e) {
                    res = "Erro: " + e.getMessage();
                }
                final String msg = res;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(LogActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
