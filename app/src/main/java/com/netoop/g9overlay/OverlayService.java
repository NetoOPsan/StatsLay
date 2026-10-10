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
import android.text.SpannableString;
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
        lp.gravity = Gravity.TOP | Gravity.START;
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
                            lp.x = startX + (int) dx;
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

    /** Aplica aparência (cores, opacidade, bordas, tamanho, toque) a partir das configurações. */
    private void applyStyle() {
        if (root == null || prefs == null) return;
        int a = Math.round(prefs.bgPercent() * 2.55f);
        int[] bg = Prefs.THEME_BG[prefs.theme()];
        int[] ac = Prefs.THEME_ACCENT[prefs.theme()];
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(a, bg[0], bg[1], bg[2]));
        g.setCornerRadius(dp(prefs.radiusDp()));
        if (a > 25) g.setStroke(dp(1), Color.argb(Math.min(110, a), ac[0], ac[1], ac[2]));
        root.setBackground(g);
        root.setPadding(dp(7), dp(5), dp(7), dp(5));

        boolean ct = prefs.clickThrough();
        int f = BASE_FLAGS | (ct ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        if (lp.flags != f) {
            lp.flags = f;
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }
        if (lastLines != null) render(lastLines);
    }

    private void togglePanel() {
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        box.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Métricas");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        p.addView(title);

        for (int i = 0; i < Prefs.KEYS.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < Prefs.KEYS.length; j++) {
                final String key = Prefs.KEYS[j];
                CheckBox cb = new CheckBox(this);
                cb.setText(Prefs.LABELS[j]);
                cb.setTextColor(Color.WHITE);
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                cb.setChecked(prefs.on(key));
                cb.setPadding(0, 0, dp(6), 0);
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        prefs.set(key, checked);
                    }
                });
                row.addView(cb, new LinearLayout.LayoutParams(dp(155), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            p.addView(row);
        }

        LinearLayout b1 = new LinearLayout(this);
        b1.setOrientation(LinearLayout.HORIZONTAL);
        b1.addView(smallButton("A-", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setTextSp(prefs.textSp() - 1);
            }
        }));
        b1.addView(smallButton("A+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setTextSp(prefs.textSp() + 1);
            }
        }));
        b1.addView(smallButton("Fundo-", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setInt("bg_pct", Math.max(0, prefs.bgPercent() - 10));
            }
        }));
        b1.addView(smallButton("Fundo+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setInt("bg_pct", Math.min(100, prefs.bgPercent() + 10));
            }
        }));
        p.addView(b1);
        LinearLayout b2 = new LinearLayout(this);
        b2.setOrientation(LinearLayout.HORIZONTAL);
        b2.addView(smallButton("Fechar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel();
            }
        }));
        b2.addView(smallButton("Config.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(OverlayService.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }));
        b2.addView(smallButton("Parar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.log("svc", "parar (painel)");
                stopSelf();
            }
        }));
        p.addView(b2);
        return p;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setOnClickListener(l);
        return b;
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
                    if (s != 0 && now - s > 10000) {
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
                } catch (Exception e) {
                    AppLog.log("rapido", "ERRO ao interpretar: " + e);
                    lines = errorLines("Erro ao interpretar: " + e);
                }
            }
            for (String note : col.drainNotes()) AppLog.log("rapido", note);

            final List<Collector.Line> toShow = lines;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    lastLines = toShow;
                    render(toShow);
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
        while (running) {
            boolean wantApp = prefs.on("app"), wantFps = prefs.on("fps");
            long now = System.currentTimeMillis();
            String cmd = probe.buildCommand(wantApp, wantFps, now);
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
                    long ms = SystemClock.elapsedRealtime() - t0;
                    if (ms > 1500) AppLog.log("lento", "ciclo lento " + ms + " ms | passos: " + slowShell.lastTimings);
                } catch (RootShell.ShellException e) {
                    slowTickStart = 0;
                    slowShell.close();
                    AppLog.log("lento", "FALHA: " + e.getMessage() + " | ms=" + e.ms + " | passos: " + e.timings);
                    AppLog.log("lento", "comando enviado: " + cmd);
                    AppLog.log("lento", "fim da saída recebida:\n" + e.tail);
                    probe.noteHang(Collector.lastHeaderFrom(e.getMessage()), now);
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

    private void render(List<Collector.Line> lines) {
        int[] ac = Prefs.THEME_ACCENT[prefs.theme()];
        int accent = Color.rgb(ac[0], ac[1], ac[2]);
        boolean colorize = prefs.colorize();
        boolean bold = prefs.bold();
        int size = prefs.textSp();

        while (lineViews.size() < lines.size()) {
            TextView t = new TextView(this);
            t.setSingleLine(true);
            t.setShadowLayer(2f, 0f, 0f, Color.BLACK);
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
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);

            // rótulo (primeira palavra) na cor de destaque; resto na cor do nível
            String text = l.text;
            int cut = text.indexOf(' ');
            if (cut <= 0) cut = text.length();
            SpannableString sp = new SpannableString(text);
            sp.setSpan(new ForegroundColorSpan(accent), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (cut < text.length()) {
                int c = colorize ? colorFor(l.level) : Color.WHITE;
                sp.setSpan(new ForegroundColorSpan(c), cut, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            t.setText(sp);
        }
    }

    private static int colorFor(int level) {
        switch (level) {
            case 1:
                return Color.rgb(255, 214, 64);   // atenção: amarelo
            case 2:
                return Color.rgb(255, 82, 82);    // crítico: vermelho
            case 3:
                return Color.rgb(160, 160, 160);  // informativo: cinza
            default:
                return Color.WHITE;
        }
    }
}
