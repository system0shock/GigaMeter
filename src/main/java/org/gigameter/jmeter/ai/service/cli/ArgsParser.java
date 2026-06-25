package org.gigameter.jmeter.ai.service.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a configured extra-args string into argument tokens, honoring single and double quotes so
 * that values containing spaces survive intact. Replaces the prototype's {@code split("\\s+")},
 * which tore quoted arguments apart.
 */
final class ArgsParser {

    private ArgsParser() {
    }

    static List<String> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0; // 0 = not in quotes, else the active quote char
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                inToken = true;
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            tokens.add(cur.toString());
        }
        return tokens;
    }
}
