package com.netoop.g9overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Tela principal — redesenhada para v1.8.
 *
 * Estrutura em abas (Início · Métricas · Aparência · GPU · Sistema) navegadas por
 * uma barra fixada no topo da ScrollView, sem precisar de fragmentos ou ViewPager.
 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());

    // Barra de status no topo
    private Button toggleBtn;
    private LinearLayout chipsRow;
    private volatile Boolean rootOk = null;

    // Abas
    private static final int TAB_HOME    = 0;
    private static final int TAB_METRICS = 1;
    private static final int TAB_LOOKS   = 2;
    private static final int TAB_GPU     = 3;
    private static final int TAB_SYSTEM  = 4;
    private static final String[] TAB_LABELS = {"Início", "Métricas", "Aparência", "GPU", "Sistema"};
    private int currentTab = TAB_HOME;
    private Button[] tabBtns;
    private LinearLayout contentArea;   // área trocada ao mudar de aba

    // Arrays de escolha (para highlight)
    private final java.util.List<Object[]> choices = new java.util.ArrayList<Object[]>();
    private Button[] intervalBtns;
    private Button[] themeBtns;
    private Button[] diagBtns;
    private Button[] presetBtns;

    private static final int[] INTERVALS      = {500, 1000, 2000, 3000};
    private static final String[] INT_LABELS  = {"0,5 s", "1 s", "2 s", "3 s"};
    private static final int[] DIAG_SECS      = {5, 15, 30, 60};
    private static final int[] ZRAM_SIZES     = {4096, 5120, 6144, 8192};
    private static final String[] ZRAM_LABELS = {"4 GB", "5 GB", "6 GB", "8 GB"};
    private static final int[] SWAPPINESS     = {60, 80, 100};
    private static final String ZMOD          = "/data/adb/modules/zram-4gb-lz4";

    private TextView zramStatus;
    private Button[] zramBtns;
    private Button[] swpBtns;
    private int cfgMb = -1, cfgSw = -1;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppLog.init(this);
        prefs = new Prefs(this);

        int accent = prefs.accentColor();
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        // Raiz total com fundo escuro
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // ── CABEÇALHO FIXO ────────────────────────────────────────────────────
        root.addView(buildHeader(accent));

        // ── BARRA DE ABAS FIXA ────────────────────────────────────────────────
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(Ui.BG);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        tabBtns = new Button[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;
            Button tb = makeTabButton(TAB_LABELS[i]);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchTab(idx); }
            });
            tabBtns[i] = tb;
            tabRow.addView(tb);
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        // Linha divisória sob as abas
        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ── ÁREA DE CONTEÚDO (rolável, troca conforme a aba) ─────────────────
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        contentArea.setPadding(p, p, p, p + dp(24));
        sv.addView(contentArea);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, svLp);

        setContentView(root);

        switchTab(TAB_HOME);   // renderiza conteúdo inicial
        refreshStatus();
        testRoot(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (currentTab == TAB_SYSTEM) loadZramCfg();
    }

    // ── CABEÇALHO ─────────────────────────────────────────────────────────────

    private View buildHeader(int accent) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int p = dp(16);
        col.setPadding(p, dp(20), p, 0);

        // Linha com título + botão principal
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView appName = new TextView(this);
        appName.setText("G9 Overlay");
        appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        appName.setTextColor(Ui.TEXT);
        appName.setTypeface(Typeface.DEFAULT_BOLD);

        // Rótulo de versão colorido
        TextView ver = new TextView(this);
        ver.setText("v" + Collector.VERSION + " · Monitor Root");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setTextColor(Ui.SUB);

        titles.addView(appName);
        titles.addView(ver);
        topRow.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Botão iniciar/parar compacto no canto
        toggleBtn = new Button(this);
        toggleBtn.setAllCaps(false);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        toggleBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleBtn.setMinWidth(0);
        toggleBtn.setMinimumWidth(0);
        toggleBtn.setStateListAnimator(null);
        updateToggleBtn(accent);
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (OverlayService.RUNNING) stopOverlay();
                else startOverlay();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(12), 0, 0, 0);
        topRow.addView(toggleBtn, tlp);
        col.addView(topRow);

        // Chips de estado (Root, Sobrepor, Ativo)
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(0, dp(14), 0, dp(6));
        col.addView(chipsRow);

        return col;
    }

    private void updateToggleBtn(int accent) {
        boolean running = OverlayService.RUNNING;
        toggleBtn.setText(running ? "⏹ Parar" : "▶ Iniciar");
        if (running) {
            GradientDrawable bg = Ui.roundStroked(
                    Color.argb(40, Ui.BAD >> 16 & 0xFF, Ui.BAD >> 8 & 0xFF, Ui.BAD & 0xFF),
                    Ui.BAD, 20, 1, this);
            toggleBtn.setBackground(bg);
            toggleBtn.setTextColor(Ui.BAD);
        } else {
            toggleBtn.setBackground(Ui.round(accent, 20, this));
            float[] hsv = new float[3];
            Color.colorToHSV(accent, hsv);
            toggleBtn.setTextColor(hsv[2] > 0.6f ? Color.rgb(8, 15, 25) : Color.WHITE);
        }
        int ph = dp(14), pv = dp(10);
        toggleBtn.setPadding(ph, pv, ph, pv);
    }

    // ── ABAS ──────────────────────────────────────────────────────────────────

    private Button makeTabButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        int ph = dp(18), pv = dp(8);
        b.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void switchTab(int tab) {
        currentTab = tab;
        int accent = prefs.accentColor();

        // Atualiza visual das abas
        for (int i = 0; i < tabBtns.length; i++) {
            if (i == tab) {
                tabBtns[i].setBackground(Ui.round(accent, 20, this));
                float[] hsv = new float[3];
                Color.colorToHSV(accent, hsv);
                tabBtns[i].setTextColor(hsv[2] > 0.6f ? Color.rgb(8, 15, 25) : Color.WHITE);
            } else {
                tabBtns[i].setBackground(Ui.roundStroked(Ui.SURFACE2, Ui.BORDER, 20, 1, this));
                tabBtns[i].setTextColor(Ui.SUB);
            }
        }

        // Limpa e preenche o conteúdo
        choices.clear();
        contentArea.removeAllViews();
        switch (tab) {
            case TAB_HOME:    buildTabHome();    break;
            case TAB_METRICS: buildTabMetrics(); break;
            case TAB_LOOKS:   buildTabLooks();   break;
            case TAB_GPU:     buildTabGpu();     break;
            case TAB_SYSTEM:  buildTabSystem();  break;
        }
        refreshChoices();
    }

    // ── ABA: INÍCIO ───────────────────────────────────────────────────────────

    private void buildTabHome() {
        int accent = prefs.accentColor();

        // ── Cartão de ação rápida: permissões ──
        LinearLayout permCard = Ui.heroCard(this, accent);
        permCard.addView(Ui.sectionTitle(this, "Permissões & acesso", accent));
        TextView pd = Ui.text(this, "Usa o root para liberar \"sobrepor apps\", rodar em segundo plano e ficar fora da economia de bateria.", 13, Ui.SUB, false);
        pd.setPadding(0, 0, 0, dp(12));
        permCard.addView(pd);
        permCard.addView(Ui.button(this, "Conceder via root (e testar)", true, accent, new View.OnClickListener() {
            @Override public void onClick(View v) { grantViaRoot(); }
        }));
        contentArea.addView(permCard);

        // ── Perfis prontos ──
        LinearLayout presetCard = Ui.card(this);
        presetCard.addView(Ui.sectionTitle(this, "Perfis rápidos", accent));
        TextView pd2 = Ui.text(this, "Aplica layout, métricas e aparência de uma vez.", 13, Ui.SUB, false);
        pd2.setPadding(0, 0, 0, dp(10));
        presetCard.addView(pd2);
        String[] presetNames = {"Completo", "GameHub", "Mínimo", "Diagnóstico", "Neon"};
        String[] presetDesc  = {"Todas as métricas", "Para jogos", "Só o essencial", "Debug total", "Estilo neon"};
        presetBtns = new Button[presetNames.length];
        // Grid 2 colunas
        for (int row = 0; row < presetNames.length; row += 2) {
            LinearLayout rowLay = new LinearLayout(this);
            rowLay.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(6));
            rowLay.setLayoutParams(rlp);
            for (int col = 0; col < 2 && row + col < presetNames.length; col++) {
                final int pi = row + col;
                LinearLayout pCard = new LinearLayout(this);
                pCard.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable pBg = Ui.roundStroked(Ui.SURFACE2, Ui.BORDER, 14, 1, this);
                pCard.setBackground(pBg);
                int pp = dp(12);
                pCard.setPadding(pp, pp, pp, pp);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                plp.setMargins(0, 0, col == 0 ? dp(6) : 0, 0);
                pCard.setLayoutParams(plp);

                TextView pName = Ui.text(this, presetNames[pi], 14, Ui.TEXT, true);
                TextView pSub  = Ui.text(this, presetDesc[pi],  11, Ui.SUB,  false);
                pCard.addView(pName);
                pCard.addView(pSub);

                pCard.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.applyPreset(pi);
                        Toast.makeText(MainActivity.this, "Perfil \"" + presetNames[pi] + "\" aplicado", Toast.LENGTH_SHORT).show();
                        recreate();
                    }
                });
                rowLay.addView(pCard);
            }
            presetCard.addView(rowLay);
        }
        contentArea.addView(presetCard);

        // ── Atalhos rápidos ──
        LinearLayout quickCard = Ui.card(this);
        quickCard.addView(Ui.sectionTitle(this, "Ações rápidas", accent));
        quickCard.addView(Ui.button(this, "Destravar GPU agora", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Soltando a GPU...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String out = Root.run(TileScripts.gpuFreeAll(), 20);
                        AppLog.init(MainActivity.this);
                        AppLog.log("ui", "destravar GPU: " + out.replace('\n', ' '));
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this,
                                        out.length() > 120 ? out.substring(0, 120) : out,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        }));
        quickCard.addView(Ui.button(this, "Registrar diagnóstico no log", false, new View.OnClickListener() {
            @Override public void onClick(View v) { diagNow(); }
        }));
        quickCard.addView(Ui.button(this, "Ver log completo", true, accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        }));
        contentArea.addView(quickCard);

        // ── Atalhos do painel ──
        LinearLayout tileCard = Ui.card(this);
        tileCard.addView(Ui.sectionTitle(this, "Atalhos no painel de notificações", accent));
        String[] tileItems = {
                "● Overlay — liga/desliga o HUD",
                "● Performance — módulo GPU+CPU",
                "● Limpar RAM — libera memória antes de jogar",
                "● Gravar GPU — snapshot no log",
                "● Destravar GPU — solta um limite por vez"
        };
        for (String item : tileItems) {
            TextView tv = Ui.text(this, item, 13, Ui.SUB, false);
            tv.setPadding(0, dp(3), 0, dp(3));
            tileCard.addView(tv);
        }
        TextView tileHint = Ui.text(this, "\nPuxe o painel → lápis (Editar) → arraste os botões para cima.", 12, Ui.SUB, false);
        tileCard.addView(tileHint);
        contentArea.addView(tileCard);
    }

    // ── ABA: MÉTRICAS ─────────────────────────────────────────────────────────

    private void buildTabMetrics() {
        int accent = prefs.accentColor();
        for (String[] g : Prefs.GROUPS) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.sectionTitle(this, g[0], accent));
            for (int i = 1; i < g.length; i++) {
                final String key = g[i];
                if (i > 1) card.addView(Ui.divider(this));
                card.addView(switchRow(Prefs.label(key), Prefs.desc(key),
                        prefs.on(key), new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton v, boolean c) {
                                prefs.set(key, c);
                            }
                        }));
            }
            contentArea.addView(card);
        }
    }

    // ── ABA: APARÊNCIA ────────────────────────────────────────────────────────

    private void buildTabLooks() {
        int accent = prefs.accentColor();

        // ── Layout e posição ──
        LinearLayout layoutCard = Ui.card(this);
        layoutCard.addView(Ui.sectionTitle(this, "Layout e posição", accent));
        layoutCard.addView(Ui.groupLabel(this, "Formato do overlay"));
        layoutCard.addView(choiceRow(new String[]{"Lista", "Barra"}, "layout", new int[]{0, 1}, 0));
        layoutCard.addView(Ui.groupLabel(this, "Estilo da barra"));
        layoutCard.addView(choiceRow(new String[]{"Contínua", "Chips"}, "bar_style", new int[]{0, 1}, 1));
        layoutCard.addView(Ui.groupLabel(this, "Âncora (posição no topo)"));
        layoutCard.addView(choiceRow(new String[]{"Esquerda", "Centro", "Direita"}, "anchor", new int[]{0, 1, 2}, 0));
        layoutCard.addView(seek("Largura máxima da barra", "bar_width", 40, 100, 96, "%"));
        contentArea.addView(layoutCard);

        // ── Tema de cor ──
        LinearLayout themeCard = Ui.card(this);
        themeCard.addView(Ui.sectionTitle(this, "Tema de cor", accent));
        // Grid de temas — 3 colunas
        themeBtns = new Button[Prefs.THEME_NAMES.length];
        LinearLayout themeRow1 = newHRow(), themeRow2 = newHRow();
        for (int i = 0; i < Prefs.THEME_NAMES.length; i++) {
            final int idx = i;
            int[] ac = Prefs.THEME_ACCENT[i];
            int tAccent = Color.rgb(ac[0], ac[1], ac[2]);
            Button tb = makeThemeChip(Prefs.THEME_NAMES[i], tAccent);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.setInt("theme", idx);
                    refreshChoices();
                    // Recarrega a aba para refletir o novo accent
                    switchTab(TAB_LOOKS);
                }
            });
            themeBtns[i] = tb;
            (i < 3 ? themeRow1 : themeRow2).addView(tb);
        }
        themeCard.addView(themeRow1);
        themeCard.addView(themeRow2);

        themeCard.addView(Ui.divider(this));
        themeCard.addView(switchRow("Cor de destaque personalizada",
                "Usa o matiz abaixo em vez da cor do tema",
                prefs.accentCustom(), boolListener("accent_on")));
        themeCard.addView(seek("Matiz da cor de destaque", "accent_hue", 0, 360, 210, "°"));
        contentArea.addView(themeCard);

        // ── Estilo visual ──
        LinearLayout styleCard = Ui.card(this);
        styleCard.addView(Ui.sectionTitle(this, "Estilo visual do overlay", accent));
        styleCard.addView(Ui.groupLabel(this, "Estilo"));
        styleCard.addView(choiceRow(Prefs.OVERLAY_STYLE_NAMES, "overlay_style", new int[]{0,1,2,3}, 0));
        styleCard.addView(seek("Glow neon (só estilo Neon)", "neon_glow", 0, 100, 60, "%"));
        styleCard.addView(Ui.divider(this));
        styleCard.addView(Ui.groupLabel(this, "Tamanho do rótulo"));
        styleCard.addView(choiceRow(new String[]{"Igual", "Menor", "Oculto"}, "label_size", new int[]{0,1,2}, 1));
        contentArea.addView(styleCard);

        // ── Tipografia ──
        LinearLayout typoCard = Ui.card(this);
        typoCard.addView(Ui.sectionTitle(this, "Tipografia", accent));
        typoCard.addView(Ui.groupLabel(this, "Fonte"));
        typoCard.addView(choiceRow(new String[]{"Mono", "Sans", "Condensada"}, "font", new int[]{0,1,2}, 0));
        typoCard.addView(seek("Tamanho do texto", "text_sp", 7, 18, 10, " sp"));
        typoCard.addView(switchRow("Texto em negrito", "Mais legível sobre jogos claros", prefs.bold(), boolListener("bold")));
        typoCard.addView(switchRow("Clock em GHz", "Exibe 1.8G em vez de 1804", prefs.ghz(), boolListener("ghz")));
        contentArea.addView(typoCard);

        // ── Fundo e bordas ──
        LinearLayout bgCard = Ui.card(this);
        bgCard.addView(Ui.sectionTitle(this, "Fundo e bordas", accent));
        bgCard.addView(seek("Opacidade do fundo",      "bg_pct",     0,  100, 60, "%"));
        bgCard.addView(seek("Opacidade do texto",      "text_alpha", 30, 100, 100, "%"));
        bgCard.addView(seek("Arredondamento",          "radius_dp",   0,  28, 10, " dp"));
        bgCard.addView(seek("Espaçamento interno",     "pad_dp",      0,  16,  6, " dp"));
        bgCard.addView(switchRow("Borda colorida",       "Contorno na cor de destaque", prefs.border(), boolListener("border")));
        bgCard.addView(switchRow("Sombra no texto",      "Ajuda a ler sobre fundos claros", prefs.shadow(), boolListener("shadow")));
        bgCard.addView(switchRow("Cores de alerta",      "Amarelo/vermelho quando algo passa do limite", prefs.colorize(), boolListener("colorize")));
        contentArea.addView(bgCard);

        // ── Extras de UX ──
        LinearLayout uxCard = Ui.card(this);
        uxCard.addView(Ui.sectionTitle(this, "Extras de UX", accent));
        uxCard.addView(switchRow("Ícones por métrica",   "Exibe emoji antes de cada linha", prefs.showIcons(),  boolListener("show_icons")));
        uxCard.addView(switchRow("Barra de progresso",   "Mini barra de uso sob CPU/GPU (modo lista)", prefs.showMiniBar(), boolListener("show_mini_bar")));
        uxCard.addView(switchRow("Vibração ao abrir painel", "Toque háptico ao abrir o painel rápido", prefs.hapticPanel(), boolListener("haptic_panel")));
        uxCard.addView(seek("Animação de atualização", "update_anim_ms", 0, 400, 120, " ms"));
        contentArea.addView(uxCard);
    }

    // ── ABA: GPU ──────────────────────────────────────────────────────────────

    private void buildTabGpu() {
        int accent = prefs.accentColor();

        LinearLayout guardCard = Ui.heroCard(this, accent);
        guardCard.addView(Ui.sectionTitle(this, "Guarda automático da GPU", accent));
        TextView gd = Ui.text(this, "Detecta quando a GPU fica presa no clock mínimo ou limitada e tenta soltá-la automaticamente. Nunca age acima da temperatura limite definida abaixo.", 13, Ui.SUB, false);
        gd.setPadding(0, 0, 0, dp(12));
        guardCard.addView(gd);
        guardCard.addView(switchRow("Guarda da GPU ativo",
                "Tenta soltar sozinho (respeita temperatura)",
                prefs.guardOn(), boolListener("guard_on")));
        guardCard.addView(seek("Temperatura limite (não age acima de)", "guard_temp", 70, 95, 88, " °C"));
        guardCard.addView(seek("Alertar temperatura em", "warn_temp_c", 40, 90, 70, " °C"));
        contentArea.addView(guardCard);

        LinearLayout fixCard = Ui.card(this);
        fixCard.addView(Ui.sectionTitle(this, "Fixação e destravamento", accent));
        fixCard.addView(switchRow("Fixar GPU no máximo",
                "Mantém o piso da GPU no teto enquanto o overlay roda (gera mais calor)",
                prefs.gpuPin(), boolListener("gpu_pin")));
        fixCard.addView(Ui.divider(this));
        fixCard.addView(Ui.button(this, "Destravar GPU agora", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Soltando a GPU...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String out = Root.run(TileScripts.gpuFreeAll(), 20);
                        AppLog.init(MainActivity.this);
                        AppLog.log("ui", "destravar GPU: " + out.replace('\n', ' '));
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this,
                                        out.length() > 120 ? out.substring(0, 120) : out,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        }));
        fixCard.addView(Ui.button(this, "Gravar estado da GPU no log", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                GpuTools.record(MainActivity.this, "botão GPU", new GpuTools.Callback() {
                    @Override public void onResult(final String summary) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, summary, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }));
        contentArea.addView(fixCard);
    }

    // ── ABA: SISTEMA ──────────────────────────────────────────────────────────

    private void buildTabSystem() {
        int accent = prefs.accentColor();

        // ── Comportamento ──
        LinearLayout behCard = Ui.card(this);
        behCard.addView(Ui.sectionTitle(this, "Comportamento", accent));
        behCard.addView(Ui.groupLabel(this, "Atualizar a cada"));
        intervalBtns = new Button[INTERVALS.length];
        LinearLayout iRow = newHRow();
        for (int i = 0; i < INTERVALS.length; i++) {
            final int ms = INTERVALS[i];
            Button ib = chipBtn(INT_LABELS[i], new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.setInt("interval_ms", ms);
                    refreshChoices();
                }
            });
            intervalBtns[i] = ib;
            iRow.addView(ib);
        }
        behCard.addView(iRow);
        behCard.addView(Ui.divider(this));
        behCard.addView(switchRow("Travar posição",       "Evita mover o overlay sem querer",       prefs.lockPos(),      boolListener("lock_pos")));
        behCard.addView(switchRow("Toque atravessa",       "O overlay não recebe toques",             prefs.clickThrough(), boolListener("click_through")));
        behCard.addView(switchRow("Iniciar com o sistema","Liga o overlay automaticamente após boot", prefs.autostart(),    boolListener("autostart")));
        behCard.addView(Ui.divider(this));
        behCard.addView(Ui.button(this, "Resetar posição do overlay", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setPos(8, 8);
                Toast.makeText(MainActivity.this, "Posição resetada — reinicie o overlay", Toast.LENGTH_SHORT).show();
            }
        }));
        contentArea.addView(behCard);

        // ── ZRAM ──
        LinearLayout zrCard = Ui.card(this);
        zrCard.addView(Ui.sectionTitle(this, "ZRAM (módulo)", accent));
        zramStatus = Ui.text(this, "Lendo configuração...", 13, Ui.SUB, false);
        zramStatus.setPadding(0, 0, 0, dp(10));
        zrCard.addView(zramStatus);
        zrCard.addView(Ui.groupLabel(this, "Tamanho"));
        zramBtns = new Button[ZRAM_SIZES.length];
        LinearLayout zRow = newHRow();
        for (int i = 0; i < ZRAM_SIZES.length; i++) {
            final int mb = ZRAM_SIZES[i];
            Button zb = chipBtn(ZRAM_LABELS[i], new View.OnClickListener() {
                @Override public void onClick(View v) { setZramCfg("ZRAM_MB", mb); }
            });
            zramBtns[i] = zb;
            zRow.addView(zb);
        }
        zrCard.addView(zRow);
        zrCard.addView(Ui.groupLabel(this, "Swappiness (preferência do kernel pelo swap)"));
        swpBtns = new Button[SWAPPINESS.length];
        LinearLayout sRow = newHRow();
        for (int i = 0; i < SWAPPINESS.length; i++) {
            final int sw = SWAPPINESS[i];
            Button sb = chipBtn(String.valueOf(sw), new View.OnClickListener() {
                @Override public void onClick(View v) { setZramCfg("SWAPPINESS", sw); }
            });
            swpBtns[i] = sb;
            sRow.addView(sb);
        }
        zrCard.addView(sRow);
        TextView zNote = Ui.text(this, "⚠ A mudança vale no próximo boot.", 12, Ui.WARN, false);
        zNote.setPadding(0, dp(6), 0, dp(4));
        zrCard.addView(zNote);
        zrCard.addView(Ui.button(this, "Reiniciar agora", false, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReboot(); }
        }));
        contentArea.addView(zrCard);

        // ── Diagnóstico ──
        LinearLayout diagCard = Ui.card(this);
        diagCard.addView(Ui.sectionTitle(this, "Diagnóstico", accent));
        TextView dd = Ui.text(this, "O app mantém um log de erros, tempos, sensores e eventos da GPU. Se algo falhar, copie e envie.", 13, Ui.SUB, false);
        dd.setPadding(0, 0, 0, dp(10));
        diagCard.addView(dd);
        diagCard.addView(switchRow("Diagnóstico automático",
                "Grava no log, de tempos em tempos, limites de CPU/GPU e sensores",
                prefs.diagOn(), boolListener("diag_on")));
        diagCard.addView(Ui.groupLabel(this, "Gravar a cada"));
        diagBtns = new Button[DIAG_SECS.length];
        LinearLayout dRow = newHRow();
        for (int i = 0; i < DIAG_SECS.length; i++) {
            final int sec = DIAG_SECS[i];
            Button db = chipBtn(sec + " s", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.setInt("diag_sec", sec);
                    refreshChoices();
                }
            });
            diagBtns[i] = db;
            dRow.addView(db);
        }
        diagCard.addView(dRow);
        diagCard.addView(Ui.button(this, "Registrar diagnóstico agora", false, new View.OnClickListener() {
            @Override public void onClick(View v) { diagNow(); }
        }));
        diagCard.addView(Ui.button(this, "Ver log completo", true, accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        }));
        contentArea.addView(diagCard);

        loadZramCfg();
    }

    // ── COMPONENTES ───────────────────────────────────────────────────────────

    private LinearLayout newHRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private View switchRow(String title, String subtitle, boolean checked,
                           CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        if (subtitle != null && subtitle.length() > 0)
            texts.addView(Ui.text(this, subtitle, 12, Ui.SUB, false));
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(12), 0, 0, 0);
        sw.setLayoutParams(slp);
        row.addView(sw);
        return row;
    }

    private CompoundButton.OnCheckedChangeListener boolListener(final String key) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean c) {
                prefs.setBool(key, c);
            }
        };
    }

    private View seek(String title, final String key, final int min, final int max,
                      int def, final String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(4));

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView label = Ui.text(this, "", 13, Ui.TEXT, false);
        labelRow.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView valBadge = Ui.badge(this, "", prefs.accentColor());
        labelRow.addView(valBadge);
        box.addView(labelRow);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur - min);
        label.setText(title);
        valBadge.setText(cur + unit);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                valBadge.setText(v + unit);
                if (fromUser) prefs.setInt(key, v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sblp.setMargins(0, dp(4), 0, 0);
        sb.setLayoutParams(sblp);
        box.addView(sb);
        return box;
    }

    private View choiceRow(String[] labels, final String key, final int[] values, int def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button[] bs = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int v = values[i];
            bs[i] = chipBtn(labels[i], new View.OnClickListener() {
                @Override public void onClick(View x) {
                    prefs.setInt(key, v);
                    refreshChoices();
                }
            });
            row.addView(bs[i]);
        }
        choices.add(new Object[]{bs, key, values, Integer.valueOf(def)});
        return row;
    }

    private Button chipBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setStateListAnimator(null);
        b.setBackground(Ui.roundStroked(Ui.SURFACE2, Ui.BORDER, 20, 1, this));
        b.setTextColor(Ui.SUB);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private Button makeThemeChip(String name, int tAccent) {
        Button b = new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setStateListAnimator(null);
        int bg = Color.argb(40, Color.red(tAccent), Color.green(tAccent), Color.blue(tAccent));
        b.setBackground(Ui.roundStroked(bg, Color.argb(120, Color.red(tAccent), Color.green(tAccent), Color.blue(tAccent)), 20, 1, this));
        b.setTextColor(tAccent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void markSelected(Button b, boolean sel) {
        int accent = prefs.accentColor();
        if (sel) {
            b.setBackground(Ui.round(accent, 20, this));
            float[] hsv = new float[3];
            Color.colorToHSV(accent, hsv);
            b.setTextColor(hsv[2] > 0.6f ? Color.rgb(8, 15, 25) : Color.WHITE);
        } else {
            b.setBackground(Ui.roundStroked(Ui.SURFACE2, Ui.BORDER, 20, 1, this));
            b.setTextColor(Ui.SUB);
        }
    }

    private void refreshChoices() {
        int accent = prefs.accentColor();
        if (intervalBtns != null) {
            int cur = prefs.intervalMs();
            for (int i = 0; i < intervalBtns.length; i++)
                markSelected(intervalBtns[i], INTERVALS[i] == cur);
        }
        if (themeBtns != null) {
            int th = prefs.theme();
            for (int i = 0; i < themeBtns.length; i++) {
                if (i == th) {
                    int[] ac = Prefs.THEME_ACCENT[i];
                    int ta = Color.rgb(ac[0], ac[1], ac[2]);
                    themeBtns[i].setBackground(Ui.round(ta, 20, this));
                    float[] hsv = new float[3];
                    Color.colorToHSV(ta, hsv);
                    themeBtns[i].setTextColor(hsv[2] > 0.6f ? Color.rgb(8, 15, 25) : Color.WHITE);
                } else {
                    int[] ac = Prefs.THEME_ACCENT[i];
                    int ta = Color.rgb(ac[0], ac[1], ac[2]);
                    int bg = Color.argb(40, ac[0], ac[1], ac[2]);
                    themeBtns[i].setBackground(Ui.roundStroked(bg,
                            Color.argb(120, ac[0], ac[1], ac[2]), 20, 1, this));
                    themeBtns[i].setTextColor(ta);
                }
            }
        }
        if (diagBtns != null) {
            int ds = prefs.diagIntervalSec();
            for (int i = 0; i < diagBtns.length; i++)
                markSelected(diagBtns[i], DIAG_SECS[i] == ds);
        }
        for (Object[] c : choices) {
            Button[] bs = (Button[]) c[0];
            int[] vals  = (int[]) c[2];
            int curVal  = prefs.getInt((String) c[1], (Integer) c[3]);
            for (int i = 0; i < bs.length; i++) markSelected(bs[i], vals[i] == curVal);
        }
        if (zramBtns != null) {
            for (int i = 0; i < zramBtns.length; i++) markSelected(zramBtns[i], ZRAM_SIZES[i] == cfgMb);
        }
        if (swpBtns != null) {
            for (int i = 0; i < swpBtns.length; i++) markSelected(swpBtns[i], SWAPPINESS[i] == cfgSw);
        }
    }

    // ── STATUS ─────────────────────────────────────────────────────────────────

    private void refreshStatus() {
        chipsRow.removeAllViews();
        boolean overlay = Settings.canDrawOverlays(this);
        chipsRow.addView(Ui.chip(this,
                rootOk == null ? "Root: verificando" : (rootOk ? "Root ativo" : "Sem root"),
                rootOk == null ? Ui.WARN : (rootOk ? Ui.GOOD : Ui.BAD)));
        chipsRow.addView(Ui.chip(this,
                overlay ? "Overlay OK" : "Overlay: falta permissão",
                overlay ? Ui.GOOD : Ui.BAD));
        chipsRow.addView(Ui.chip(this,
                OverlayService.RUNNING ? "HUD ativo" : "HUD parado",
                OverlayService.RUNNING ? Ui.GOOD : Ui.SUB));
        updateToggleBtn(prefs.accentColor());
    }

    // ── AÇÕES ─────────────────────────────────────────────────────────────────

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda a permissão de sobrepor apps primeiro.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        AppLog.log("ui", "iniciar overlay");
        startForegroundService(new Intent(this, OverlayService.class));
        h.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 600);
    }

    private void stopOverlay() {
        AppLog.log("ui", "parar overlay");
        stopService(new Intent(this, OverlayService.class));
        h.postDelayed(new Runnable() {
            @Override public void run() { refreshStatus(); }
        }, 400);
    }

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

    private void testRoot(final boolean grant) {
        final String pkg = getPackageName();
        new Thread(new Runnable() {
            @Override public void run() {
                String id = su("id");
                boolean ok = id.contains("uid=0");
                AppLog.log("ui", "teste de root: " + id);
                if (ok && grant) {
                    String r = su("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                            + "dumpsys deviceidle whitelist +" + pkg);
                    AppLog.log("ui", "permissões via root: " + r.replace('\n', ' '));
                }
                rootOk = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshStatus();
                        if (grant) Toast.makeText(MainActivity.this,
                                rootOk ? "Permissões concedidas" : "Sem root", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void grantViaRoot() {
        rootOk = null;
        refreshStatus();
        testRoot(true);
    }

    // ── ZRAM ──────────────────────────────────────────────────────────────────

    private void loadZramCfg() {
        new Thread(new Runnable() {
            @Override public void run() {
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
                    else if (l.startsWith("ZRAM_MB="))   mb    = toInt(l.substring(8));
                    else if (l.startsWith("SWAPPINESS=")) sw    = toInt(l.substring(11));
                    else if (l.startsWith("ACTIVE=")) {
                        try { actMb = (int)(Long.parseLong(l.substring(7).trim()) / 1048576L); }
                        catch (Exception ignored) {}
                    } else if (l.startsWith("ACTSW=")) actSw = toInt(l.substring(6));
                }
                final boolean fMod = mod, fHave = haveCfg;
                final int fMb = mb, fSw = sw, fActMb = actMb, fActSw = actSw;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (zramStatus == null) return;
                        cfgMb = fMb;
                        cfgSw = fSw;
                        String t;
                        if (!fMod) t = "Módulo zram-4gb-lz4 não encontrado.";
                        else if (!fHave) t = "Módulo antigo (sem config.txt). Atualize para v1.3+.";
                        else {
                            t = "Ativo: " + (fActMb > 0 ? fActMb + " MB" : "?")
                                    + " · swappiness " + (fActSw > 0 ? fActSw : "?")
                                    + "\nConfigurado: " + (fMb > 0 ? fMb + " MB" : "padrão")
                                    + " · swappiness " + (fSw > 0 ? fSw : "padrão");
                            if ((fMb > 0 && fActMb > 0 && fMb != fActMb)
                                    || (fSw > 0 && fActSw > 0 && fSw != fActSw)) {
                                t += "\n→ reinicie para aplicar";
                            }
                        }
                        zramStatus.setText(t);
                        refreshChoices();
                    }
                });
            }
        }).start();
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return -1; }
    }

    private void setZramCfg(final String key, final int value) {
        new Thread(new Runnable() {
            @Override public void run() {
                String r = su("f=" + ZMOD + "/config.txt; [ -d " + ZMOD + " ] || { echo NOMOD; exit 0; }; "
                        + "if grep -q '^" + key + "=' \"$f\" 2>/dev/null; then sed -i 's/^" + key + "=.*/" + key + "=" + value + "/' \"$f\"; "
                        + "else echo '" + key + "=" + value + "' >> \"$f\"; fi; echo OK");
                AppLog.log("ui", "config ZRAM " + key + "=" + value + " -> " + r.replace('\n', ' '));
                final boolean ok = r.contains("OK");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                ok ? "Salvo. Vale no próximo boot." : "Falhou: módulo não encontrado",
                                Toast.LENGTH_LONG).show();
                        loadZramCfg();
                    }
                });
            }
        }).start();
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle("Reiniciar agora?")
                .setMessage("O celular vai reiniciar para aplicar as configurações de ZRAM. Salve o que estiver aberto.")
                .setPositiveButton("Reiniciar", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        AppLog.log("ui", "reiniciando (pedido do usuário)");
                        new Thread(new Runnable() {
                            @Override public void run() { su("sync; reboot"); }
                        }).start();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void diagNow() {
        Toast.makeText(this, "Gravando diagnóstico no log...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                AppLog.init(MainActivity.this);
                long now = System.currentTimeMillis();
                DiagProbe d = new DiagProbe();
                String out = Root.run(RootShell.PRELUDE + d.buildCommand(now, true), 25);
                if (out.startsWith("ERRO")) {
                    AppLog.log("diag-agora", "falhou: " + out);
                } else {
                    for (String l : d.parse(out, now)) AppLog.log("diag-agora", l);
                }
                GpuTools.record(MainActivity.this, "botão do app", new GpuTools.Callback() {
                    @Override public void onResult(final String summary) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Diagnóstico gravado", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        }, "g9ov-diag-now").start();
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
