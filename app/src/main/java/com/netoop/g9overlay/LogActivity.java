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
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Mostra o log do app, com botões para atualizar, copiar, compartilhar, exportar (root) e limpar. */
public class LogActivity extends Activity {

    private TextView tv;
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        final int p = Ui.dp(this, 12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(p, p, p, p);

        root.addView(Ui.text(this, "Log do app", 22, Ui.TEXT, true));
        TextView path = Ui.text(this, AppLog.path(), 10, Ui.SUB, false);
        path.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(path);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(small("Atualizar", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));
        row1.addView(small("Copiar", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));
        row1.addView(small("Compartilhar", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        }));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(small("Exportar (root)", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportRoot();
            }
        }));
        row2.addView(small("Limpar", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear();
                AppLog.log("log", "log limpo");
                refresh();
            }
        }));
        root.addView(row1);
        root.addView(row2);

        sv = new ScrollView(this);
        tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        tv.setTextColor(Color.rgb(200, 210, 220));
        tv.setTextIsSelectable(true);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tv);
        sv.addView(hs);
        sv.setBackground(Ui.round(Color.rgb(10, 12, 16), 10, this));
        sv.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        root.addView(sv, lp);

        setContentView(root);
        refresh();
    }

    private android.widget.Button small(String label, boolean primary, View.OnClickListener l) {
        android.widget.Button bt = Ui.button(this, label, primary, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        bt.setLayoutParams(lp);
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return bt;
    }

    private void refresh() {
        final String t = AppLog.tail(60000);
        tv.setText(t);
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
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

    /** Copia o arquivo de log para /sdcard/Download usando root (fácil de achar no gerenciador de arquivos). */
    private void exportRoot() {
        final String src = AppLog.path();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String res;
                try {
                    Process pr = new ProcessBuilder("su", "-c",
                            "cp '" + src + "' /sdcard/Download/g9overlay-log.txt && chmod 666 /sdcard/Download/g9overlay-log.txt && echo OK")
                            .redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    pr.waitFor();
                    res = sb.toString().contains("OK") ? "Exportado: Download/g9overlay-log.txt" : "Falhou: " + sb;
                } catch (Exception e) {
                    res = "Erro: " + e.getMessage();
                }
                final String msg = res;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(LogActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
