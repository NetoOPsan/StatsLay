package com.netoop.g9overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.service.quicksettings.TileService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Serviço em primeiro plano: desenha a janela flutuante e atualiza os números.
 *
 * Dois shells root independentes:
 *   - RÁPIDO: leituras baratas (RAM, CPU, GPU, temperaturas...) a cada ciclo;
 *   - LENTO : descobrir o app em foco e medir FPS (comandos externos, mais arriscados).
 * Se o lento travar, o overlay principal continua funcionando.
 *
 * Toque na janela = abre o painel. Arrastar = mover (a menos que a posição esteja travada).
 */
public class OverlayService extends Service {

    /** A tela principal usa isto para mostrar "ativo/parado". */
    public static volatile boolean RUNNING = false;

    private static final String ACTION_STOP = "STOP";
    private static final String CHANNEL = "g9ov";
    private static final int BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RootShell fastShell = new RootShell("rapido");
    private final RootShell slowShell = new RootShell("lento");
    private SlowProbe probe;
    private Prefs prefs;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout box;      // linhas de métricas
    private LinearLayout panel;    // painel de configuração rápida
    private final List<TextView> lineViews = new ArrayList<TextView>();
    private List<Collector.Line> lastLines = null;
    private List<Segs.Seg> lastSegs = null;
    private final GpuGuard guard = new GpuGuard();
    private volatile boolean pinApplied = false;
    private int lastAnchor = -1;

    // guardamos o listener num campo: o Android só mantém referência fraca
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private volatile boolean running = false;
    private volatile long fastTickStart = 0, slowTickStart = 0;
    private boolean started = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.init(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppLog.log("svc", "parar (notificação)");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            // Quem chama startForegroundService() PRECISA de startForeground() logo em seguida.
            prefs = new Prefs(this);
            startForeground(1, buildNotification());
            AppLog.log("svc", "iniciando v" + Collector.VERSION + " | " + Build.MANUFACTURER + " " + Build.MODEL
                    + " | Android SDK " + Build.VERSION.SDK_INT);
            if (!Settings.canDrawOverlays(this)) {
                AppLog.log("svc", "SEM permissão de sobrepor apps; encerrando");
                Toast.makeText(this, "Falta a permissão de sobrepor apps (use o botão no app).", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            started = true;
            RUNNING = true;
            pokeTile();
            probe = new SlowProbe(getPackageName());
            createOverlay();
            prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    applyStyle(); // aparência muda ao vivo, enquanto você mexe nos controles
                }
            };
            prefs.registerListener(prefListener);
            startThreads();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.log("svc", "onDestroy");
        running = false;
        RUNNING = false;
        pokeTile();
        if (pinApplied) {
            pinApplied = false;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Root.run(TileScripts.gpuPin(false), 15);
                }
            }).start();
        }
        fastShell.close();
        slowShell.close();
        if (prefs != null && prefListener != null) prefs.unregisterListener(prefListener);
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    /** Pede ao painel de notificações para atualizar o tile do overlay (ativo/parado). */
    private void pokeTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, OverlayTile.class));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ notificação

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN));
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("G9 Overlay ativo")
                .setContentText("Toque para abrir as configurações")
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", pi)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------ janela

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private void createOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                BASE_FLAGS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravityFor(prefs.anchor());
        lastAnchor = prefs.anchor();
        lp.x = prefs.posX();
        lp.y = prefs.posY();

        root = new FrameLayout(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        panel = buildPanel();
        panel.setVisibility(View.GONE);
        root.addView(panel);

        // toque = abre/fecha o painel; arrastar = move a janela
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        root.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (prefs.lockPos()) return true;
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true;
                        if (moved) {
                            lp.x = startX + (prefs.anchor() == 2 ? -1 : 1) * (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(root, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) prefs.setPos(lp.x, lp.y);
                        else togglePanel();
                        return true;
                    default:
                        return true;
                }
            }
        });

        wm.addView(root, lp);
        applyStyle();
    }

    private static int gravityFor(int anchor) {
        int h = anchor == 1 ? Gravity.CENTER_HORIZONTAL : (anchor == 2 ? Gravity.END : Gravity.START);
        return Gravity.TOP | h;
    }

    /** Aplica aparência (cores, opacidade, bordas, espaçamento, âncora, toque) a partir das configurações. */
    private void applyStyle() {
        if (root == null || prefs == null) return;
        int a = Math.round(prefs.bgPercent() * 2.55f);
        int[] bg = Prefs.THEME_BG[prefs.theme()];
        int accent = prefs.accentColor();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(a, bg[0], bg[1], bg[2]));
        g.setCornerRadius(dp(prefs.radiusDp()));
        if (prefs.border() && a > 25) {
            g.setStroke(dp(1), Color.argb(Math.min(120, a), Color.red(accent), Color.green(accent), Color.blue(accent)));
        }
        root.setBackground(g);
        int pad = dp(prefs.padDp());
        root.setPadding(pad + dp(1), pad - dp(1) < 0 ? 0 : pad - dp(1), pad + dp(1), pad - dp(1) < 0 ? 0 : pad - dp(1));

        boolean changed = false;
        int an = prefs.anchor();
        if (an != lastAnchor) {                     // trocou a âncora: aplica a posição salva
            lastAnchor = an;
            lp.gravity = gravityFor(an);
            lp.x = prefs.posX();
            lp.y = prefs.posY();
            changed = true;
        }
        boolean ct = prefs.clickThrough();
        int f = BASE_FLAGS | (ct ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        if (lp.flags != f) {
            lp.flags = f;
            changed = true;
        }
        if (changed) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }
        renderAll();
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        if (show && prefs.hapticPanel()) {
            try {
                android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vib.vibrate(android.os.VibrationEffect.createOneShot(30,
                                android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vib.vibrate(30);
                    }
                }
            } catch (Exception ignored) {}
        }
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        box.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private LinearLayout buildPanel() {
        int accent = prefs.accentColor();
        int panelBg = prefs.panelBg() == 1
                ? Color.argb(230, 8, 10, 16)
                : Color.argb(220,
                    Prefs.THEME_BG[prefs.theme()][0],
                    Prefs.THEME_BG[prefs.theme()][1],
                    Prefs.THEME_BG[prefs.theme()][2]);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(panelBg);
        pbg.setCornerRadius(dp(16));
        pbg.setStroke(dp(1), Color.argb(80,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        p.setBackground(pbg);
        int pp = dp(12);
        p.setPadding(pp, pp, pp, pp);

        // ── Cabeçalho do painel ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(this);
        title.setText("G9 Overlay");
        title.setTextColor(accent);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setLetterSpacing(0.08f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Botão fechar (X) no canto
        Button closeBtn = panelIconBtn("✕", accent, new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanel(); }
        });
        header.addView(closeBtn);
        p.addView(header);

        // Divider
        View div0 = new View(this);
        div0.setBackgroundColor(Color.argb(50, 255, 255, 255));
        p.addView(div0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ── Métricas em grid compacto de 2 colunas ──
        TextView metTitle = new TextView(this);
        metTitle.setText("MÉTRICAS");
        metTitle.setTextColor(Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)));
        metTitle.setTypeface(Typeface.DEFAULT_BOLD);
        metTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        metTitle.setLetterSpacing(0.12f);
        metTitle.setPadding(0, dp(8), 0, dp(4));
        p.addView(metTitle);

        for (int i = 0; i < Prefs.KEYS.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < Prefs.KEYS.length; j++) {
                final String key = Prefs.KEYS[j];
                CheckBox cb = new CheckBox(this);
                cb.setText(Prefs.LABELS[j]);
                cb.setTextColor(Color.rgb(200, 210, 225));
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                cb.setChecked(prefs.on(key));
                cb.setPadding(0, dp(1), dp(6), dp(1));
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        prefs.set(key, checked);
                    }
                });
                row.addView(cb, new LinearLayout.LayoutParams(dp(148), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            p.addView(row);
        }

        // ── Controles de aparência rápidos ──
        View div1 = new View(this);
        div1.setBackgroundColor(Color.argb(50, 255, 255, 255));
        LinearLayout.LayoutParams d1lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        d1lp.setMargins(0, dp(8), 0, dp(8));
        p.addView(div1, d1lp);

        TextView quickTitle = new TextView(this);
        quickTitle.setText("APARÊNCIA RÁPIDA");
        quickTitle.setTextColor(Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)));
        quickTitle.setTypeface(Typeface.DEFAULT_BOLD);
        quickTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        quickTitle.setLetterSpacing(0.12f);
        quickTitle.setPadding(0, 0, 0, dp(4));
        p.addView(quickTitle);

        LinearLayout b1 = new LinearLayout(this);
        b1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams b1lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b1lp.setMargins(0, 0, 0, dp(4));
        b1.setLayoutParams(b1lp);

        b1.addView(panelChipBtn("A−", accent, new View.OnClickListener() {
            @Override public void onClick(View v) { prefs.setTextSp(prefs.textSp() - 1); }
        }));
        b1.addView(panelChipBtn("A+", accent, new View.OnClickListener() {
            @Override public void onClick(View v) { prefs.setTextSp(prefs.textSp() + 1); }
        }));
        b1.addView(panelChipBtn("Fundo−", accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setInt("bg_pct", Math.max(0, prefs.bgPercent() - 10));
            }
        }));
        b1.addView(panelChipBtn("Fundo+", accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setInt("bg_pct", Math.min(100, prefs.bgPercent() + 10));
            }
        }));
        p.addView(b1);

        // ── Ações GPU ──
        View div2 = new View(this);
        div2.setBackgroundColor(Color.argb(50, 255, 255, 255));
        LinearLayout.LayoutParams d2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        d2lp.setMargins(0, dp(4), 0, dp(8));
        p.addView(div2, d2lp);

        LinearLayout gpuRow = new LinearLayout(this);
        gpuRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.setMargins(0, 0, 0, dp(4));
        gpuRow.setLayoutParams(glp);
        gpuRow.addView(panelChipBtn("Gravar GPU", accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                GpuTools.record(OverlayService.this, "painel do overlay", new GpuTools.Callback() {
                    @Override public void onResult(final String summary) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(OverlayService.this, summary, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        }));
        gpuRow.addView(panelChipBtn("Destravar GPU", accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(OverlayService.this, "Tentando soltar a GPU...", Toast.LENGTH_SHORT).show();
                GpuTools.unstick(OverlayService.this, new GpuTools.Callback() {
                    @Override public void onResult(final String summary) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(OverlayService.this, summary, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }));
        p.addView(gpuRow);

        // ── Navegação: Config / Parar ──
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.addView(panelChipBtn("⚙ Config.", accent, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(OverlayService.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }));
        navRow.addView(panelChipBtn("⏹ Parar overlay", Color.rgb(252, 100, 100), new View.OnClickListener() {
            @Override public void onClick(View v) {
                AppLog.log("svc", "parar (painel)");
                stopSelf();
            }
        }));
        p.addView(navRow);

        return p;
    }

    /** Botão chip para o painel do overlay (estilo discreto com a cor de destaque). */
    private Button panelChipBtn(String label, int accent, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(34)); b.setMinimumHeight(dp(34));
        b.setStateListAnimator(null);
        int bg = Color.argb(35, Color.red(accent), Color.green(accent), Color.blue(accent));
        int stroke = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), stroke);
        b.setBackground(gd);
        b.setTextColor(accent);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    /** Botão ícone pequeno (ex.: fechar painel). */
    private Button panelIconBtn(String label, int accent, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(dp(32)); b.setMinimumHeight(dp(32));
        b.setStateListAnimator(null);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)));
        gd.setCornerRadius(dp(12));
        b.setBackground(gd);
        b.setTextColor(Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent)));
        int pp = dp(10);
        b.setPadding(pp, dp(4), pp, dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        return panelChipBtn(label, prefs.accentColor(), l);
    }

    // ------------------------------------------------------------ threads

    private void startThreads() {
        running = true;
        spawn("g9ov-fast", new Runnable() {
            @Override
            public void run() {
                fastLoop();
            }
        });
        spawn("g9ov-slow", new Runnable() {
            @Override
            public void run() {
                slowLoop();
            }
        });
        // Vigia: ciclo rápido preso > 15 s ou lento preso > 10 s -> derruba o su correspondente
        spawn("g9ov-dog", new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long now = SystemClock.elapsedRealtime();
                    long f = fastTickStart, s = slowTickStart;
                    if (f != 0 && now - f > 15000) {
                        AppLog.log("dog", "ciclo RÁPIDO preso há " + (now - f) + " ms; fechando su");
                        fastShell.close();
                    }
                    if (s != 0 && now - s > 15000) {
                        AppLog.log("dog", "ciclo LENTO preso há " + (now - s) + " ms; fechando su");
                        slowShell.close();
                    }
                }
            }
        });
    }

    private void spawn(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void fastLoop() {
        Collector col = new Collector(probe);
        long prevCpuMs = android.os.Process.getElapsedCpuTime();
        long prevWall = SystemClock.elapsedRealtime();
        int n = 0, errs = 0;
        long sum = 0, max = 0;
        boolean first = true;
        String lastSet = "";
        AppLog.log("rapido", "loop iniciado");
        while (running) {
            long t0 = SystemClock.elapsedRealtime();
            Set<String> en = prefs.enabledSet();
            if (prefs.guardOn()) {          // o guarda precisa ler a GPU e a temperatura mesmo com essas linhas desligadas
                en.add("gpudata");
                en.add("tempdata");
            }
            String setStr = new TreeSet<String>(en).toString();
            if (!setStr.equals(lastSet)) {
                lastSet = setStr;
                AppLog.log("rapido", "métricas ligadas: " + setStr);
            }
            List<Collector.Line> lines = null;
            String out = null, cmd = null;
            try {
                if (!fastShell.isOpen()) {
                    if (fastShell.open()) {
                        AppLog.log("rapido", "su aberto");
                    } else {
                        AppLog.log("rapido", "FALHA ao abrir su");
                        lines = errorLines("Sem root (su). Autorize o app no KernelSU.");
                    }
                }
                if (lines == null) {
                    fastTickStart = t0;
                    cmd = col.buildCommand(en, android.os.Process.myPid());
                    out = fastShell.run(cmd);
                    fastTickStart = 0;
                    col.resetHangs();
                    long ms = SystemClock.elapsedRealtime() - t0;
                    n++;
                    sum += ms;
                    if (ms > max) max = ms;
                    if (first) {
                        first = false;
                        AppLog.log("rapido", "primeiro ciclo OK: " + ms + " ms, " + out.length() + " bytes");
                    }
                    if (ms > 1000) AppLog.log("rapido", "ciclo lento " + ms + " ms | passos: " + fastShell.lastTimings);
                }
            } catch (RootShell.ShellException e) {
                fastTickStart = 0;
                fastShell.close();
                errs++;
                AppLog.log("rapido", "FALHA: " + e.getMessage() + " | ms=" + e.ms + " | passos: " + e.timings);
                AppLog.log("rapido", "comando enviado: " + cmd);
                AppLog.log("rapido", "fim da saída recebida:\n" + e.tail);
                String ign = col.noteHang(Collector.lastHeaderFrom(e.getMessage()));
                if (ign != null) AppLog.log("rapido", "passei a ignorar: " + ign);
                lines = errorLines("Erro: " + e.getMessage() + (ign != null ? " | (ignorando " + ign + ")" : ""));
            } catch (Exception e) {
                fastTickStart = 0;
                fastShell.close();
                errs++;
                AppLog.log("rapido", "ERRO: " + e);
                lines = errorLines("Erro: " + e.getMessage());
            }
            if (lines == null) {
                try {
                    long cpuMs = android.os.Process.getElapsedCpuTime();
                    long wall = SystemClock.elapsedRealtime();
                    double selfPct = wall > prevWall ? 100.0 * (cpuMs - prevCpuMs) / (wall - prevWall) : 0;
                    prevCpuMs = cpuMs;
                    prevWall = wall;
                    lines = col.parse(out, System.currentTimeMillis(), readSelfRssKb(), selfPct);
                    // caixa-preta da GPU: guarda 1 amostra por ciclo e avisa quando a GPU parece presa/limitada
                    GpuBlackBox.Sample gs = col.lastGpuSample();
                    if (gs != null) {
                        String alert = GpuBlackBox.INSTANCE.push(gs);
                        if (alert != null) {
                            AppLog.log("gpu", "ALERTA AUTOMÁTICO: " + alert);
                            GpuTools.record(OverlayService.this, "alerta automático", null);
                        }
                    }
                } catch (Exception e) {
                    AppLog.log("rapido", "ERRO ao interpretar: " + e);
                    lines = errorLines("Erro ao interpretar: " + e);
                }
            }
            for (String note : col.drainNotes()) AppLog.log("rapido", note);

            // guarda da GPU: solta a GPU sozinho quando ela está limitada/presa (com limite de temperatura)
            try {
                GpuHealth.Result gh = col.lastGpuHealth();
                GpuBlackBox.Sample gs2 = col.lastGpuSample();
                if (gh != null && gs2 != null && prefs.guardOn()) runGuard(gh, gs2);
                maintainPin();
            } catch (Exception e) {
                AppLog.log("guarda", "ERRO: " + e);
            }

            final List<Collector.Line> toShow = lines;
            final List<Segs.Seg> toSegs = Segs.build(lines, prefs.ghz(), col.lastGpuHealth());
            ui.post(new Runnable() {
                @Override
                public void run() {
                    lastLines = toShow;
                    lastSegs = toSegs;
                    renderAll();
                }
            });

            if (n > 0 && n % 60 == 0) {
                AppLog.log("stats", "rápido: média " + (sum / n) + " ms, máx " + max + " ms, " + n + " ciclos, "
                        + errs + " erros | overlay: " + (readSelfRssKb() / 1024) + " MB");
            }
            long wait = prefs.intervalMs() - (SystemClock.elapsedRealtime() - t0);
            try {
                Thread.sleep(Math.max(100, wait));
            } catch (InterruptedException e) {
                return;
            }
        }
        fastShell.close();
    }

    private void slowLoop() {
        AppLog.log("lento", "loop iniciado");
        DiagProbe diag = new DiagProbe();
        while (running) {
            boolean wantApp = prefs.on("app"), wantFps = prefs.on("fps");
            long now = System.currentTimeMillis();
            String cmd = probe.buildCommand(wantApp, wantFps, now);
            // diagnóstico automático (os mesmos valores que eu pedia pelo Termux), a cada N segundos
            boolean diagNow = prefs.diagOn() && diag.due(now, prefs.diagIntervalSec());
            if (diagNow) cmd = cmd + diag.buildCommand(now, false);
            if (cmd.length() > 0) {
                try {
                    if (!slowShell.isOpen()) {
                        if (slowShell.open()) {
                            AppLog.log("lento", "su aberto");
                        } else {
                            AppLog.log("lento", "FALHA ao abrir su");
                            sleepQuiet(3000);
                            continue;
                        }
                    }
                    long t0 = SystemClock.elapsedRealtime();
                    slowTickStart = t0;
                    String out = slowShell.run(cmd);
                    slowTickStart = 0;
                    probe.parse(out, now);
                    if (diagNow) {
                        for (String l : diag.parse(out, now)) AppLog.log("diag", l);
                    }
                    long ms = SystemClock.elapsedRealtime() - t0;
                    if (ms > 1500) AppLog.log("lento", "ciclo lento " + ms + " ms | passos: " + slowShell.lastTimings);
                } catch (RootShell.ShellException e) {
                    slowTickStart = 0;
                    slowShell.close();
                    AppLog.log("lento", "FALHA: " + e.getMessage() + " | ms=" + e.ms + " | passos: " + e.timings);
                    AppLog.log("lento", "comando enviado: " + cmd);
                    AppLog.log("lento", "fim da saída recebida:\n" + e.tail);
                    String h = Collector.lastHeaderFrom(e.getMessage());
                    if (DiagProbe.ownsHeader(h)) {
                        // travou no diagnóstico (não na busca do app): só ele é afetado
                        String msg = diag.noteHang(h, now);
                        if (msg != null) AppLog.log("lento", msg);
                    } else {
                        probe.noteHang(h, now);
                    }
                } catch (Exception e) {
                    slowTickStart = 0;
                    slowShell.close();
                    AppLog.log("lento", "ERRO: " + e);
                }
                for (String note : probe.drainNotes()) AppLog.log("lento", note);
            }
            sleepQuiet(wantFps ? 1000 : 2000);
        }
        slowShell.close();
    }

    // ------------------------------------------------------------ guarda da GPU e fixação do piso

    private void runGuard(final GpuHealth.Result gh, GpuBlackBox.Sample gs) {
        long now = System.currentTimeMillis();
        GpuGuard.Decision d = guard.decide(gh, gs.gpuTemp, now, prefs.guardTemp());
        switch (d) {
            case ACT:
                AppLog.log("guarda", "GPU " + gh.text + " (temp " + gs.gpuTemp + "): tentando soltar");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String out = Root.run(TileScripts.gpuFreeAll(), 20);
                        AppLog.log("guarda", "resultado: " + out.replace('\n', ' '));
                    }
                }, "g9ov-guard").start();
                break;
            case HOT_PAUSE:
                AppLog.log("guarda", "GPU " + gh.text + " mas a " + gs.gpuTemp + " °C (limite " + prefs.guardTemp()
                        + "): NÃO mexo, deixo a proteção do kernel agir");
                break;
            case NO_TEMP:
                AppLog.log("guarda", "sem leitura da temperatura da GPU: o guarda não age por segurança");
                break;
            case GAVE_UP:
                AppLog.log("guarda", "3 tentativas sem sucesso: pausando o guarda por 10 min (algo mais forte limita a GPU)");
                break;
            default:
                break;
        }
    }

    /** Liga/desliga a fixação do piso da GPU conforme a configuração (só enquanto o overlay roda). */
    private void maintainPin() {
        final boolean want = prefs.gpuPin();
        if (want == pinApplied) return;
        pinApplied = want;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Root.run(TileScripts.gpuPin(want), 15);
                AppLog.log("guarda", "piso da GPU " + (want ? "fixado" : "restaurado") + ": " + out.replace('\n', ' '));
            }
        }, "g9ov-pin").start();
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private List<Collector.Line> errorLines(String msg) {
        List<Collector.Line> l = new ArrayList<Collector.Line>();
        l.add(new Collector.Line("G9 Overlay v" + Collector.VERSION, 3));
        for (String part : msg.split(" \\| ")) l.add(new Collector.Line(part, 2));
        return l;
    }

    private long readSelfRssKb() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/status"));
            String s;
            while ((s = r.readLine()) != null) {
                if (s.startsWith("VmRSS:")) return Long.parseLong(s.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ desenho das linhas

    /** Escolhe o desenho conforme o layout: barra horizontal (se houver segmentos) ou lista vertical. */
    private void renderAll() {
        if (lastLines == null) return;
        if (prefs.layout() == 1 && lastSegs != null && !lastSegs.isEmpty()) renderBar(lastSegs);
        else render(lastLines);
    }

    private Typeface fontFace(boolean bold) {
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        switch (prefs.font()) {
            case 1:
                return Typeface.create("sans-serif-medium", style);
            case 2:
                return Typeface.create("sans-serif-condensed", style);
            default:
                return Typeface.create(Typeface.MONOSPACE, style);
        }
    }

    private int withAlpha(int color) {
        return Color.argb(Math.round(prefs.textAlphaPct() * 2.55f), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void styleText(TextView t) {
        t.setSingleLine(true);
        if (prefs.shadow()) t.setShadowLayer(2f, 0f, 0f, Color.BLACK);
        else t.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }

    private void render(List<Collector.Line> lines) {
        int accent = withAlpha(prefs.accentColor());
        boolean colorize = prefs.colorize();
        boolean bold = prefs.bold();
        int size = prefs.textSp();

        if (box.getChildCount() != lineViews.size()) {   // veio da barra: recomeça
            box.removeAllViews();
            lineViews.clear();
        }
        box.setOrientation(LinearLayout.VERTICAL);
        while (lineViews.size() < lines.size()) {
            TextView t = new TextView(this);
            box.addView(t);
            lineViews.add(t);
        }
        while (lineViews.size() > lines.size()) {
            TextView t = lineViews.remove(lineViews.size() - 1);
            box.removeView(t);
        }
        for (int i = 0; i < lines.size(); i++) {
            Collector.Line l = lines.get(i);
            TextView t = lineViews.get(i);
            styleText(t);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            t.setTypeface(fontFace(bold));

            // rótulo (primeira palavra) na cor de destaque; resto na cor do nível
            String text = l.text;
            int cut = text.indexOf(' ');
            if (cut <= 0) cut = text.length();
            SpannableString sp = new SpannableString(text);
            sp.setSpan(new ForegroundColorSpan(accent), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (cut < text.length()) {
                int c = colorize || l.level == 4 ? withAlpha(colorFor(l.level)) : withAlpha(Color.WHITE);
                sp.setSpan(new ForegroundColorSpan(c), cut, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            t.setText(sp);
        }
    }

    /** Layout em barra horizontal (estilo GameHub): chips com rótulo + valor, quebrando em mais linhas se faltar largura. */
    private void renderBar(List<Segs.Seg> segs) {
        int accent = prefs.accentColor();
        int accentText = withAlpha(accent);
        boolean colorize = prefs.colorize();
        boolean chips = prefs.barStyle() == 1;
        int size = prefs.textSp();
        int pad = dp(prefs.padDp());
        lineViews.clear();
        box.removeAllViews();
        box.setOrientation(LinearLayout.VERTICAL);

        int maxW = Math.round(getResources().getDisplayMetrics().widthPixels * prefs.barWidthPct() / 100f) - 2 * pad - dp(4);
        LinearLayout row = newRow();
        box.addView(row);
        int rowW = 0;
        for (Segs.Seg s : segs) {
            TextView t = new TextView(this);
            styleText(t);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            t.setTypeface(fontFace(prefs.bold()));
            SpannableStringBuilder b = new SpannableStringBuilder();
            if (s.dot >= 0) {
                int st = b.length();
                b.append("● ");
                b.setSpan(new ForegroundColorSpan(colorFor(s.dot)), st, st + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int ls = b.length();
            b.append(s.label);
            b.setSpan(new ForegroundColorSpan(accentText), ls, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new StyleSpan(Typeface.BOLD), ls, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new RelativeSizeSpan(0.82f), ls, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(' ');
            int vs = b.length();
            b.append(s.value);
            int vc = colorize ? colorFor(s.level) : Color.WHITE;
            b.setSpan(new ForegroundColorSpan(withAlpha(vc)), vs, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(b);
            if (chips) {
                GradientDrawable cg = new GradientDrawable();
                cg.setColor(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)));
                cg.setCornerRadius(dp(8));
                t.setBackground(cg);
                t.setPadding(dp(7), dp(2), dp(7), dp(2));
            }
            LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpC.setMargins(dp(chips ? 2 : 4), dp(1), dp(chips ? 2 : 4), dp(1));
            t.setLayoutParams(lpC);
            t.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = t.getMeasuredWidth() + dp(chips ? 4 : 9);
            if (rowW + w > maxW && row.getChildCount() > 0) {
                row = newRow();
                box.addView(row);
                rowW = 0;
            }
            if (!chips && row.getChildCount() > 0) {
                View div = new View(this);
                div.setBackgroundColor(Color.argb(70, 255, 255, 255));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(1), dp(Math.max(10, size + 2)));
                dl.gravity = Gravity.CENTER_VERTICAL;
                row.addView(div, dl);
            }
            row.addView(t);
            rowW += w;
        }
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private static int colorFor(int level) {
        switch (level) {
            case 1:
                return Color.rgb(255, 214, 64);   // atenção: amarelo
            case 2:
                return Color.rgb(255, 82, 82);    // crítico: vermelho
            case 3:
                return Color.rgb(160, 160, 160);  // informativo: cinza
            case 4:
                return Color.rgb(80, 220, 130);   // tudo certo: verde
            default:
                return Color.WHITE;
        }
    }
}
