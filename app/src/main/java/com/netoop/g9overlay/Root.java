package com.netoop.g9overlay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Executa um comando como root uma vez e devolve a saída (com limite de tempo). */
public final class Root {

    private Root() {
    }

    public static String run(String script) {
        return run(script, 20);
    }

    public static String run(String script, int timeoutSec) {
        try {
            final Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            final StringBuilder sb = new StringBuilder();
            // lê em outra thread, para poder aplicar o limite de tempo
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String l;
                        while ((l = r.readLine()) != null) {
                            synchronized (sb) {
                                sb.append(l).append('\n');
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "g9ov-root-read");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy();
                return "ERRO: tempo esgotado";
            }
            reader.join(1500);
            synchronized (sb) {
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return "ERRO: " + e.getMessage();
        }
    }
}
