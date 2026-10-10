package com.netoop.g9overlay;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Log do próprio app. Grava num arquivo (com rodízio de tamanho) e pode ser visto/copiado na tela "Ver log".
 * Arquivo: /sdcard/Android/data/com.netoop.g9overlay/files/g9overlay.log
 */
public final class AppLog {

    private static final long MAX_FILE = 256 * 1024;
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    // Uma thread só grava no arquivo (daemon: não segura o processo).
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "g9ov-log");
            t.setDaemon(true);
            return t;
        }
    });

    private AppLog() {
    }

    public static synchronized void init(Context c) {
        if (file != null) return;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "g9overlay.log");
    }

    public static synchronized String path() {
        return file == null ? "(log não iniciado)" : file.getAbsolutePath();
    }

    public static void log(String tag, String msg) {
        final String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " [" + tag + "] " + msg + "\n";
        }
        IO.execute(new Runnable() {
            @Override
            public void run() {
                append(line);
            }
        });
    }

    private static synchronized void append(String line) {
        if (file == null) return;
        try {
            if (file.length() > MAX_FILE) {
                File old = new File(file.getPath() + ".1");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            FileWriter w = new FileWriter(file, true);
            try {
                w.write(line);
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** Últimos maxChars caracteres do log (espera as gravações pendentes terminarem). */
    public static String tail(int maxChars) {
        try {
            IO.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get();
        } catch (Exception ignored) {
        }
        synchronized (AppLog.class) {
            if (file == null || !file.exists()) return "(log vazio)";
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    long len = file.length();
                    long skip = Math.max(0, len - maxChars * 2L); // UTF-8: até ~2 bytes por caractere
                    in.skip(skip);
                    byte[] buf = new byte[(int) (len - skip)];
                    int n = 0;
                    while (n < buf.length) {
                        int r = in.read(buf, n, buf.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    String s = new String(buf, 0, n, "UTF-8");
                    if (s.length() > maxChars) s = s.substring(s.length() - maxChars);
                    return s;
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                return "(erro lendo log: " + e.getMessage() + ")";
            }
        }
    }

    public static synchronized void clear() {
        if (file != null) {
            file.delete();
            new File(file.getPath() + ".1").delete();
        }
    }
}
