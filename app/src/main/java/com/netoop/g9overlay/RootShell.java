package com.netoop.g9overlay;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * Um único shell root ("su") que fica aberto. A cada ciclo mandamos UMA linha de comando e lemos
 * a resposta até o marcador. Assim não criamos um processo su novo a cada segundo (isso seria caro).
 */
public class RootShell {

    private static final String END = "<<END>>";

    private volatile Process proc;
    private BufferedWriter out;
    private BufferedReader in;

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
            // r ARQUIVO: imprime "##ARQUIVO" e o conteúdo. Só usa comandos internos do shell
            // (sem criar processos), então é muito barato.
            out.write("r() { [ -r \"$1\" ] || return; echo \"##$1\"; "
                    + "while IFS= read -r l || [ -n \"$l\" ]; do printf '%s\\n' \"$l\"; l=; done < \"$1\"; }\n");
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** Executa um comando no shell root e devolve tudo que ele imprimiu. */
    public synchronized String run(String cmd) throws IOException {
        if (proc == null) throw new IOException("su fechado");
        out.write(cmd);
        out.write("\necho '" + END + "'\n");
        out.flush();
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(END)) return sb.toString();
            sb.append(line).append('\n');
        }
        throw new IOException("su encerrou");
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
