package org.gigameter.jmeter.ai.service.ops;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Best-effort repair of JSON that an LLM/stream truncated at the very end — the common failure is a
 * few missing closing brackets (and occasionally an unterminated trailing string). Scans the text
 * tracking string/escape state, then appends the closers needed to balance any still-open
 * arrays/objects. Only ever <i>appends</i>; if the input is already balanced it is returned
 * unchanged, so well-formed JSON is never altered.
 */
public final class JsonRepair {

    private JsonRepair() {
    }

    /** Returns {@code json} with any missing trailing {@code "}/{@code ]}/{@code }} appended. */
    public static String closeTruncated(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '[':
                case '{':
                    stack.push(c);
                    break;
                case ']':
                    if (!stack.isEmpty() && stack.peek() == '[') {
                        stack.pop();
                    }
                    break;
                case '}':
                    if (!stack.isEmpty() && stack.peek() == '{') {
                        stack.pop();
                    }
                    break;
                default:
                    break;
            }
        }
        if (!inString && stack.isEmpty()) {
            return json; // already balanced
        }
        StringBuilder sb = new StringBuilder(json);
        // Drop a dangling trailing comma so the appended close marker stays valid JSON.
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && sb.charAt(end - 1) == ',') {
            sb.setLength(end - 1);
        }
        if (inString) {
            sb.append('"');
        }
        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '[' ? ']' : '}');
        }
        return sb.toString();
    }
}
