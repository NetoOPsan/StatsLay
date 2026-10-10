package com.netoop.g9overlay;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayDeque;

/**
 * Um shell root ("su") que fica aberto. A cada ciclo mandamos UMA linha de comando e lemos
 * a resposta até o marcador. Assim não criamos um processo su novo a cada segundo.
 */
public class RootShell {

    private static final String END = "<<END>>";

    /** Erro de leitura com tudo que ajuda a depurar (último arquivo lido, tempos, fim da saída). */
    public static class ShellException extends IOException {
        public final String last;
        public final int lines;
        public final long ms;
        public final String tail;
        public final String timings;

        ShellException(String msg, String last, int lines, long ms, String tail, String timings) {
            super(msg + " | ultimo=" + last + " | linhas=" + lines);
            this.last = last;
            this.lines = lines;
            this.ms = ms;
            this.tail = tail;
            this.timings = timings;
        }
    }

    private final String name;
    private volatile Process proc;
    private BufferedWriter out;
    private BufferedReader in;

    /** Tempos dos passos do último comando bem-sucedido: "cabeçalho@ms ...". */
    public volatile String lastTimings = "";

    public RootShell(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public boolean isOpen() {
        return proc != null;
    }

    public synchronized boolean open() {
        close();
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            proc = p;
            out = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            // IMPORTANTE: nada de chamar a função de "r"! No mksh (o sh do Android) "r" é um alias
            // padrão de "fc -e -", e o alias vence a função.
            // g9r ARQUIVO: imprime "##ARQUIVO" e o conteúdo, só com comandos internos do shell.
            out.write("g9r() { [ -r \"$1\" ] || return; echo \"##$1\"; "
                    + "while IFS= read -r l || [ -n \"$l\" ]; do printf '%s\\n' \"$l\"; l=; done < \"$1\"; }\n");
            // g9t SEGUNDOS comando...: roda com limite de tempo (se o 'timeout' existir).
            out.write("g9t() { if command -v timeout >/dev/null 2>&1; then timeout \"$@\"; else shift; \"$@\"; fi; }\n");
            // erros do shell e dos comandos não entram na saída que lemos
            out.write("exec 2>/dev/null\n");
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /**
     * Executa um comando no shell root e devolve tudo que ele imprimiu.
     * Em caso de falha/travamento lança ShellException com o último cabeçalho lido.
     */
    public synchronized String run(String cmd) throws IOException {
        if (proc == null) throw new IOException("su fechado");
        long t0 = System.nanoTime();
        String last = "";
        int n = 0;
        StringBuilder timings = new StringBuilder();
        ArrayDeque<String> tail = new ArrayDeque<String>();
        try {
            out.write(cmd);
            out.write("\necho '" + END + "'\n");
            out.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                n++;
                if (line.equals(END)) {
                    lastTimings = timings.toString();
                    return sb.toString();
                }
                if (line.startsWith("##")) {
                    last = line.substring(2);
                    if (timings.length() < 1500) {
                        timings.append(last.length() > 40 ? last.substring(0, 40) : last)
                                .append('@').append((System.nanoTime() - t0) / 1000000).append("ms ");
                    }
                }
                sb.append(line).append('\n');
                tail.addLast(line);
                if (tail.size() > 15) tail.removeFirst();
            }
        } catch (IOException e) {
            throw fail(e.getMessage(), last, n, t0, tail, timings);
        }
        throw fail("su encerrou", last, n, t0, tail, timings);
    }

    private static ShellException fail(String msg, String last, int n, long t0, ArrayDeque<String> tail, StringBuilder timings) {
        StringBuilder t = new StringBuilder();
        for (String s : tail) {
            t.append(s.length() > 160 ? s.substring(0, 160) : s).append('\n');
        }
        return new ShellException(msg, last, n, (System.nanoTime() - t0) / 1000000, t.toString(), timings.toString());
    }

    /** Pode ser chamado de outra thread (watchdog): destrói o processo e libera o run() preso. */
    public void close() {
        Process p = proc;
        proc = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        }
    }
}
