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
            // IMPORTANTE: nada de chamar a função de "r"! No mksh (o sh do Android) "r" é um alias
            // padrão de "fc -e -", e o alias vence a função: a leitura nunca funcionava.
            // g9r ARQUIVO: imprime "##ARQUIVO" e o conteúdo, só com comandos internos do shell
            // (sem criar processos), então é muito barato.
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
     * Se travar/falhar, a mensagem do erro inclui o ÚLTIMO arquivo que começou a ser lido
     * ("ultimo=..."), que é o suspeito de ter travado.
     */
    public synchronized String run(String cmd) throws IOException {
        if (proc == null) throw new IOException("su fechado");
        String last = "";
        int n = 0;
        try {
            out.write(cmd);
            out.write("\necho '" + END + "'\n");
            out.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                n++;
                if (line.equals(END)) return sb.toString();
                if (line.startsWith("##")) last = line.substring(2);
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " | ultimo=" + last + " | linhas=" + n);
        }
        throw new IOException("su encerrou | ultimo=" + last + " | linhas=" + n);
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
