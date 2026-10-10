package com.netoop.g9overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Tela principal: iniciar/parar, permissões, métricas, aparência, comportamento e diagnóstico. */
public class MainActivity extends Activity {

    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Button toggleBtn;
    private LinearLayout chips;
    private volatile Boolean rootOk = null; // null = ainda não testado
    private Button[] intervalBtns;
    private Button[] themeBtns;
    private static final String ZMOD = "/data/adb/modules/zram-4gb-lz4";
    private static final int[] ZRAM_SIZES = {4096, 5120, 6144, 8192};
    private static final String[] ZRAM_LABELS = {"4 GB", "5 GB", "6 GB", "8 GB"};
    private static final int[] SWAPPINESS_OPTS = {60, 80, 100};
    private TextView zramStatus;
    private Button[] zramBtns;
    private Button[] swpBtns;
    private int cfgMb = -1, cfgSw = -1; // valores do config.txt do módulo (-1 = desconhecido)
    private static final int[] INTERVALS = {500, 1000, 2000, 3000};
    private static final String[] INTERVAL_LABELS = {"0,5 s", "1 s", "2 s", "3 s"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        // ---- cabeçalho
        col.addView(Ui.text(this, "G9 Overlay", 28, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Monitor de desempenho com root · v" + Collector.VERSION, 13, Ui.SUB, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 14));
        col.addView(sub);

        // ---- estado + botão principal
        LinearLayout status = Ui.card(this);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        status.addView(chips);
        toggleBtn = Ui.button(this, "Iniciar overlay", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (OverlayService.RUNNING) stopOverlay();
                else startOverlay();
            }
        });
        status.addView(toggleBtn);
        col.addView(status);

        // ---- permissões
        LinearLayout perm = Ui.card(this);
        perm.addView(Ui.cardTitle(this, "Permissões"));
        TextView pd = Ui.text(this, "Usa o root para liberar \"sobrepor apps\", rodar em segundo plano e ficar fora da economia de bateria.", 13, Ui.SUB, false);
        pd.setPadding(0, 0, 0, Ui.dp(this, 6));
        perm.addView(pd);
        perm.addView(Ui.button(this, "Conceder via root (e testar)", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grantViaRoot();
            }
        }));
        col.addView(perm);

        // ---- métricas, por grupo
        for (String[] g : Prefs.GROUPS) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.cardTitle(this, g[0]));
            for (int i = 1; i < g.length; i++) {
                final String key = g[i];
                card.addView(switchRow(Prefs.label(key), Prefs.desc(key), prefs.on(key), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton v, boolean c) {
                        prefs.set(key, c);
                    }
                }));
            }
            col.addView(card);
        }

        // ---- aparência
        LinearLayout look = Ui.card(this);
        look.addView(Ui.cardTitle(this, "Aparência"));
        look.addView(Ui.text(this, "Tema", 14, Ui.TEXT, false));
        LinearLayout themes = new LinearLayout(this);
        themes.setOrientation(LinearLayout.HORIZONTAL);
        themeBtns = new Button[Prefs.THEME_NAMES.length];
        for (int i = 0; i < Prefs.THEME_NAMES.length; i++) {
            final int idx = i;
            themeBtns[i] = chipButton(Prefs.THEME_NAMES[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setInt("theme", idx);
                    refreshChoices();
                }
            });
            themes.addView(themeBtns[i]);
        }
        look.addView(themes);
        look.addView(seek("Opacidade do fundo", "bg_pct", 0, 100, 60, "%"));
        look.addView(seek("Tamanho do texto", "text_sp", 7, 18, 10, " sp"));
        look.addView(seek("Arredondamento", "radius_dp", 0, 24, 10, " dp"));
        look.addView(switchRow("Cores de alerta", "Amarelo/vermelho quando algo passa do limite", prefs.colorize(),
                boolListener("colorize")));
        look.addView(switchRow("Texto em negrito", "Mais legível sobre jogos claros", prefs.bold(), boolListener("bold")));
        col.addView(look);

        // ---- comportamento
        LinearLayout beh = Ui.card(this);
        beh.addView(Ui.cardTitle(this, "Comportamento"));
        beh.addView(Ui.text(this, "Atualizar a cada", 14, Ui.TEXT, false));
        LinearLayout ints = new LinearLayout(this);
        ints.setOrientation(LinearLayout.HORIZONTAL);
        intervalBtns = new Button[INTERVALS.length];
        for (int i = 0; i < INTERVALS.length; i++) {
            final int ms = INTERVALS[i];
            intervalBtns[i] = chipButton(INTERVAL_LABELS[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setInt("interval_ms", ms);
                    refreshChoices();
                }
            });
            ints.addView(intervalBtns[i]);
        }
        beh.addView(ints);
        beh.addView(switchRow("Travar posição", "Evita mover o overlay sem querer ao tocar", prefs.lockPos(), boolListener("lock_pos")));
        beh.addView(switchRow("Toque atravessa", "O overlay não recebe toques (o jogo recebe tudo)", prefs.clickThrough(), boolListener("click_through")));
        beh.addView(switchRow("Iniciar com o sistema", "Liga o overlay sozinho depois do boot", prefs.autostart(), boolListener("autostart")));
        beh.addView(Ui.button(this, "Resetar posição", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setPos(8, 8);
                Toast.makeText(MainActivity.this, "Posição resetada (reinicie o overlay)", Toast.LENGTH_SHORT).show();
            }
        }));
        col.addView(beh);

        // ---- atalhos no painel de notificações
        LinearLayout tl = Ui.card(this);
        tl.addView(Ui.cardTitle(this, "Atalhos no painel"));
        TextView td = Ui.text(this, "O app tem 3 botões para o painel de notificações:\n"
                + "• Overlay: liga/desliga o overlay\n"
                + "• Performance: liga/desliga o módulo Performance GPU+CPU (útil quando esquenta)\n"
                + "• Limpar RAM: limpa a memória antes de jogar\n\n"
                + "Para adicionar: puxe o painel de notificações, toque no lápis (editar) e arraste os três para a área de cima.",
                13, Ui.SUB, false);
        tl.addView(td);
        col.addView(tl);

        // ---- ZRAM (edita o config.txt do módulo; vale no próximo boot)
        LinearLayout zr = Ui.card(this);
        zr.addView(Ui.cardTitle(this, "ZRAM (módulo)"));
        zramStatus = Ui.text(this, "Lendo configuração...", 13, Ui.SUB, false);
        zramStatus.setPadding(0, 0, 0, Ui.dp(this, 8));
        zr.addView(zramStatus);
        zr.addView(Ui.text(this, "Tamanho", 14, Ui.TEXT, false));
        LinearLayout zrow = new LinearLayout(this);
        zrow.setOrientation(LinearLayout.HORIZONTAL);
        zramBtns = new Button[ZRAM_SIZES.length];
        for (int i = 0; i < ZRAM_SIZES.length; i++) {
            final int mb = ZRAM_SIZES[i];
            zramBtns[i] = chipButton(ZRAM_LABELS[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setZramCfg("ZRAM_MB", mb);
                }
            });
            zrow.addView(zramBtns[i]);
        }
        zr.addView(zrow);
        zr.addView(Ui.text(this, "Swappiness (quanto o kernel prefere usar o swap)", 14, Ui.TEXT, false));
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        swpBtns = new Button[SWAPPINESS_OPTS.length];
        for (int i = 0; i < SWAPPINESS_OPTS.length; i++) {
            final int sw = SWAPPINESS_OPTS[i];
            swpBtns[i] = chipButton(String.valueOf(sw), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setZramCfg("SWAPPINESS", sw);
                }
            });
            srow.addView(swpBtns[i]);
        }
        zr.addView(srow);
        TextView zn = Ui.text(this, "A mudança vale no PRÓXIMO BOOT: refazer o ZRAM com o jogo aberto não cabe na RAM.", 12, Ui.SUB, false);
        zn.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        zr.addView(zn);
        zr.addView(Ui.button(this, "Reiniciar agora", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReboot();
            }
        }));
        col.addView(zr);

        // ---- diagnóstico
        LinearLayout diag = Ui.card(this);
        diag.addView(Ui.cardTitle(this, "Diagnóstico"));
        TextView dd = Ui.text(this, "O app guarda um log de tudo que acontece (erros, tempos, sensores encontrados). Se algo falhar, copie o log e envie.", 13, Ui.SUB, false);
        dd.setPadding(0, 0, 0, Ui.dp(this, 6));
        diag.addView(dd);
        diag.addView(Ui.button(this, "Ver log", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        }));
        col.addView(diag);

        setContentView(sv);
        refreshChoices();
        testRoot(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        loadZramCfg();
    }

    // ------------------------------------------------------------ peças de interface

    private View switchRow(String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        if (subtitle != null && subtitle.length() > 0) texts.addView(Ui.text(this, subtitle, 12, Ui.SUB, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private CompoundButton.OnCheckedChangeListener boolListener(final String key) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean c) {
                prefs.setBool(key, c);
            }
        };
    }

    private View seek(String title, final String key, final int min, final int max, int def, final String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 8), 0, 0);
        final TextView label = Ui.text(this, "", 14, Ui.TEXT, false);
        box.addView(label);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = prefs.getInt(key, def);
        cur = Math.max(min, Math.min(max, cur));
        sb.setProgress(cur - min);
        label.setText(title + ": " + cur + unit);
        final String t = title;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                label.setText(t + ": " + v + unit);
                if (fromUser) prefs.setInt(key, v); // o overlay muda ao vivo
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
        return box;
    }

    private Button chipButton(String label, View.OnClickListener l) {
        Button b = Ui.button(this, label, false, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4));
        b.setLayoutParams(lp);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextSize(13);
        return b;
    }

    private void markSelected(Button b, boolean sel) {
        if (sel) {
            b.setBackground(Ui.round(Ui.ACCENT, 12, this));
            b.setTextColor(android.graphics.Color.rgb(8, 20, 28));
        } else {
            android.graphics.drawable.GradientDrawable g = Ui.round(android.graphics.Color.rgb(32, 38, 48), 12, this);
            g.setStroke(Ui.dp(this, 1), android.graphics.Color.argb(90, 255, 255, 255));
            b.setBackground(g);
            b.setTextColor(Ui.TEXT);
        }
    }

    private void refreshChoices() {
        int cur = prefs.intervalMs();
        for (int i = 0; i < intervalBtns.length; i++) markSelected(intervalBtns[i], INTERVALS[i] == cur);
        int th = prefs.theme();
        for (int i = 0; i < themeBtns.length; i++) markSelected(themeBtns[i], i == th);
    }

    private void refreshStatus() {
        chips.removeAllViews();
        boolean overlay = Settings.canDrawOverlays(this);
        chips.addView(Ui.chip(this, rootOk == null ? "Root: testando" : (rootOk ? "Root OK" : "Sem root"),
                rootOk == null ? Ui.WARN : (rootOk ? Ui.GOOD : Ui.BAD)));
        chips.addView(Ui.chip(this, overlay ? "Sobrepor OK" : "Sobrepor: falta", overlay ? Ui.GOOD : Ui.BAD));
        chips.addView(Ui.chip(this, OverlayService.RUNNING ? "Ativo" : "Parado", OverlayService.RUNNING ? Ui.GOOD : Ui.SUB));
        toggleBtn.setText(OverlayService.RUNNING ? "Parar overlay" : "Iniciar overlay");
    }

    // ------------------------------------------------------------ ações

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda as permissões primeiro (ou ative \"sobrepor\" manualmente).", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        AppLog.log("ui", "iniciar overlay");
        startForegroundService(new Intent(this, OverlayService.class));
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 600);
    }

    private void stopOverlay() {
        AppLog.log("ui", "parar overlay");
        stopService(new Intent(this, OverlayService.class));
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 400);
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

    /** Testa o root (e, se grant=true, libera as permissões). */
    private void testRoot(final boolean grant) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String id = su("id");
                boolean ok = id.contains("uid=0");
                AppLog.log("ui", "teste de root: " + id);
                if (ok && grant) {
                    // sobrepor apps + rodar em segundo plano + fora da economia de bateria
                    String r = su("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                            + "dumpsys deviceidle whitelist +" + pkg);
                    AppLog.log("ui", "permissões via root: " + r.replace('\n', ' '));
                }
                rootOk = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        if (grant) {
                            Toast.makeText(MainActivity.this, rootOk ? "Permissões concedidas" : "Sem root", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ ZRAM (config.txt do módulo)

    /** Lê o config.txt do módulo de ZRAM e o estado ATUAL do kernel (via root). */
    private void loadZramCfg() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = su("if [ -d " + ZMOD + " ]; then echo MOD=1; "
                        + "[ -f " + ZMOD + "/config.txt ] && echo HAVECFG=1 && cat " + ZMOD + "/config.txt; "
                        + "echo ACTIVE=$(cat /sys/block/zram0/disksize 2>/dev/null); "
                        + "echo ACTSW=$(cat /proc/sys/vm/swappiness 2>/dev/null); else echo MOD=0; fi");
                boolean mod = false, haveCfg = false;
                int mb = -1, sw = -1, actMb = -1, actSw = -1;
                for (String l : out.split("\n")) {
                    l = l.trim();
                    if (l.equals("MOD=1")) mod = true;
                    else if (l.equals("HAVECFG=1")) haveCfg = true;
                    else if (l.startsWith("ZRAM_MB=")) mb = toInt(l.substring(8));
                    else if (l.startsWith("SWAPPINESS=")) sw = toInt(l.substring(11));
                    else if (l.startsWith("ACTIVE=")) {
                        try {
                            actMb = (int) (Long.parseLong(l.substring(7).trim()) / 1048576L);
                        } catch (Exception ignored) {
                        }
                    } else if (l.startsWith("ACTSW=")) actSw = toInt(l.substring(6));
                }
                final boolean fMod = mod, fHave = haveCfg;
                final int fMb = mb, fSw = sw, fActMb = actMb, fActSw = actSw;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cfgMb = fMb;
                        cfgSw = fSw;
                        String t;
                        if (!fMod) t = "Módulo de ZRAM não encontrado (zram-4gb-lz4). Instale o zip do módulo.";
                        else if (!fHave) t = "Módulo de ZRAM antigo (sem config.txt). Instale o zram-4gb-lz4-ksu-v1.3.zip.";
                        else {
                            t = "Ativo agora: " + (fActMb > 0 ? fActMb + " MB" : "?") + " · swappiness " + (fActSw > 0 ? fActSw : "?")
                                    + "\nConfigurado: " + (fMb > 0 ? fMb + " MB" : "padrão") + " · swappiness " + (fSw > 0 ? fSw : "padrão");
                            if ((fMb > 0 && fActMb > 0 && fMb != fActMb) || (fSw > 0 && fActSw > 0 && fSw != fActSw)) {
                                t += "\n→ reinicie para aplicar";
                            }
                        }
                        zramStatus.setText(t);
                        for (int i = 0; i < zramBtns.length; i++) markSelected(zramBtns[i], ZRAM_SIZES[i] == cfgMb);
                        for (int i = 0; i < swpBtns.length; i++) markSelected(swpBtns[i], SWAPPINESS_OPTS[i] == cfgSw);
                    }
                });
            }
        }).start();
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Grava CHAVE=valor no config.txt do módulo (cria a linha se não existir). */
    private void setZramCfg(final String key, final int value) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r = su("f=" + ZMOD + "/config.txt; [ -d " + ZMOD + " ] || { echo NOMOD; exit 0; }; "
                        + "if grep -q '^" + key + "=' \"$f\" 2>/dev/null; then sed -i 's/^" + key + "=.*/" + key + "=" + value + "/' \"$f\"; "
                        + "else echo '" + key + "=" + value + "' >> \"$f\"; fi; echo OK");
                AppLog.log("ui", "config ZRAM " + key + "=" + value + " -> " + r.replace('\n', ' '));
                final boolean ok = r.contains("OK");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, ok ? "Salvo. Vale no próximo boot." : "Falhou: módulo de ZRAM não encontrado", Toast.LENGTH_LONG).show();
                        loadZramCfg();
                    }
                });
            }
        }).start();
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar agora?")
                .setMessage("O celular vai reiniciar para aplicar o ZRAM novo. Salve o que estiver aberto.")
                .setPositiveButton("Reiniciar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        AppLog.log("ui", "reiniciando o aparelho (pedido do usuário)");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                su("sync; reboot");
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void grantViaRoot() {
        rootOk = null;
        refreshStatus();
        testRoot(true);
    }
}
