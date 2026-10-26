package com.netoop.g9overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Divide a saída do shell root em seções. Dois formatos convivem:
 *   "##nome"            -> começa a seção "nome"; as linhas seguintes pertencem a ela
 *   "/caminho:conteúdo" -> linha produzida por "grep -H" (g9g): pertence à seção "/caminho"
 * Sem dependência do Android.
 */
final class Out {

    private Out() {
    }

    static Map<String, List<String>> sections(String out) {
        Map<String, List<String>> sec = new HashMap<String, List<String>>();
        List<String> cur = null;
        for (String ln : out.split("\n")) {
            if (ln.startsWith("##")) {
                cur = new ArrayList<String>();
                sec.put(ln.substring(2).trim(), cur);
                continue;
            }
            if (ln.length() > 2 && ln.charAt(0) == '/') {
                int i = ln.indexOf(':');
                if (i > 1) {
                    String path = ln.substring(0, i);
                    List<String> l = sec.get(path);
                    if (l == null) {
                        l = new ArrayList<String>();
                        sec.put(path, l);
                    }
                    l.add(ln.substring(i + 1));
                    continue;
                }
            }
            if (cur != null) cur.add(ln);
        }
        return sec;
    }
}
