package com.netoop.g9overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
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

/**
 * Serviço em primeiro plano que desenha a janela flutuante e atualiza os números.
 * Toque na janela = abre o painel para ligar/desligar métricas. Arrastar = mover.
 */
public class OverlayService extends Service {

    private static final String ACTION_STOP = "STOP";
    private static final String CHANNEL = "g9ov";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RootShell shell = new RootShell();
    private Prefs prefs;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private LinearLayout box;      // linhas de métricas
    private LinearLayout panel;    // painel de configuração
    private final List<TextView> lineViews = new ArrayList<TextView>();

    private volatile boolean running = false;
    private volatile long tickStart = 0;
    private boolean started = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            // Quem chama startForegroundService() PRECISA de startForeground() logo em seguida,
            // senão o Android derruba o app. Por isso a notificação vem antes de qualquer checagem.
            prefs = new Prefs(this);
            startForeground(1, buildNotification());
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Falta a permissão de sobrepor apps (use o botão no app).", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            started = true;
            createOverlay();
            startPolling();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        shell.close();
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ notificação

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_MIN));
        Intent stop = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("G9 Overlay ativo")
                .setContentText("Toque em Parar para encerrar")
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", pi)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------ janela

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void createOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.posX();
        lp.y = prefs.posY();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(150, 0, 0, 0));
        root.setPadding(dp(5), dp(3), dp(5), dp(3));

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
        title.setText("Métricas (toque em Fechar)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        p.addView(title);

        // duas colunas de caixas de seleção
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
                row.addView(cb, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            p.addView(row);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("A-", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setTextSp(prefs.textSp() - 1);
                applyTextSize();
            }
        }));
        buttons.addView(smallButton("A+", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setTextSp(prefs.textSp() + 1);
                applyTextSize();
            }
        }));
        buttons.addView(smallButton("Fechar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel();
            }
        }));
        buttons.addView(smallButton("Parar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        }));
        p.addView(buttons);
        return p;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void applyTextSize() {
        for (TextView t : lineViews) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSp());
    }

    // ------------------------------------------------------------ atualização

    private void startPolling() {
        running = true;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "g9ov-poll");
        worker.setDaemon(true);
        worker.start();

        // vigia: se um ciclo travar por mais de 8 s (ex.: dumpsys preso), derruba o su e reabre
        Thread dog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long t = tickStart;
                    if (t != 0 && SystemClock.elapsedRealtime() - t > 8000) shell.close();
                }
            }
        }, "g9ov-dog");
        dog.setDaemon(true);
        dog.start();
    }

    private void loop() {
        Collector col = new Collector(getPackageName());
        long prevCpuMs = android.os.Process.getElapsedCpuTime();
        long prevWall = SystemClock.elapsedRealtime();
        while (running) {
            long t0 = SystemClock.elapsedRealtime();
            List<Collector.Line> lines;
            try {
                if (!shell.isOpen() && !shell.open()) {
                    lines = errorLines("Sem root (su). Autorize o app no KernelSU.");
                } else {
                    tickStart = t0;
                    String cmd = col.buildCommand(prefs.enabledSet(), android.os.Process.myPid());
                    String out = shell.run(cmd);
                    tickStart = 0;
                    long cpuMs = android.os.Process.getElapsedCpuTime();
                    long wall = SystemClock.elapsedRealtime();
                    double selfPct = wall > prevWall ? 100.0 * (cpuMs - prevCpuMs) / (wall - prevWall) : 0;
                    prevCpuMs = cpuMs;
                    prevWall = wall;
                    lines = col.parse(out, System.currentTimeMillis(), readSelfRssKb(), selfPct);
                }
            } catch (Exception e) {
                tickStart = 0;
                shell.close();
                lines = errorLines("Erro: " + e.getMessage());
            }
            final List<Collector.Line> toShow = lines;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    render(toShow);
                }
            });
            long wait = prefs.intervalMs() - (SystemClock.elapsedRealtime() - t0);
            try {
                Thread.sleep(Math.max(100, wait));
            } catch (InterruptedException e) {
                return;
            }
        }
        shell.close();
    }

    private List<Collector.Line> errorLines(String msg) {
        List<Collector.Line> l = new ArrayList<Collector.Line>();
        l.add(new Collector.Line(msg, 2));
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

    private void render(List<Collector.Line> lines) {
        while (lineViews.size() < lines.size()) {
            TextView t = new TextView(this);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSp());
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
            t.setText(l.text);
            t.setTextColor(colorFor(l.level));
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
