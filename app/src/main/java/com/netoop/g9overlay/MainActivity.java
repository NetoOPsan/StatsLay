package com.netoop.g9overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Tela de controle: permissões via root, iniciar/parar e escolher métricas. */
public class MainActivity extends Activity {

    private Prefs prefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("G9 Overlay");
        title.setTextSize(22);
        col.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);

        col.addView(button("1) Conceder permissões (root)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grantViaRoot();
            }
        }));
        col.addView(button("2) Iniciar overlay", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startOverlay();
            }
        }));
        col.addView(button("Parar overlay", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                Toast.makeText(MainActivity.this, "Overlay parado", Toast.LENGTH_SHORT).show();
            }
        }));

        TextView h = new TextView(this);
        h.setText("\nMétricas (também dá para mudar tocando no overlay):");
        col.addView(h);
        for (int i = 0; i < Prefs.KEYS.length; i++) {
            final String key = Prefs.KEYS[i];
            CheckBox cb = new CheckBox(this);
            cb.setText(Prefs.LABELS[i]);
            cb.setChecked(prefs.on(key));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton v, boolean checked) {
                    prefs.set(key, checked);
                }
            });
            col.addView(cb);
        }

        TextView h2 = new TextView(this);
        h2.setText("\nIntervalo de atualização:");
        col.addView(h2);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(intervalButton("0,5 s", 500));
        row.addView(intervalButton("1 s", 1000));
        row.addView(intervalButton("2 s", 2000));
        col.addView(row);

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button intervalButton(String label, final int ms) {
        return button(label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setIntervalMs(ms);
                Toast.makeText(MainActivity.this, "Intervalo: " + ms + " ms", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText("Sobrepor apps: " + (overlay ? "OK" : "FALTA") + "\nRoot: toque em 1) para testar.");
    }

    /** Executa um comando como root, uma vez, e devolve a saída. */
    private static String su(String script) {
        try {
            Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERRO: " + e.getMessage();
        }
    }

    private void grantViaRoot() {
        final String pkg = getPackageName();
        status.setText("Pedindo root... (aceite o aviso do KernelSU)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 1) libera "sobrepor apps"; 2) deixa rodar em segundo plano; 3) tira da economia de bateria
                final String id = su("id");
                su("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                        + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                        + "dumpsys deviceidle whitelist +" + pkg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean root = id.contains("uid=0");
                        boolean overlay = Settings.canDrawOverlays(MainActivity.this);
                        status.setText("Root: " + (root ? "OK" : "SEM ROOT (" + id + ")")
                                + "\nSobrepor apps: " + (overlay ? "OK" : "FALTA"));
                    }
                });
            }
        }).start();
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Use o botão 1) primeiro (ou conceda manualmente).", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(new Intent(this, OverlayService.class));
    }
}
