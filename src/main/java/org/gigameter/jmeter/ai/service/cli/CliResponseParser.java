package org.gigameter.jmeter.ai.service.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Extracts the assistant's reply text from CLI output.
 *
 * <p>Replaces the prototype's brittle {@code "content":"..."} regex, which did not unescape
 * {@code \\uXXXX} (so every Cyrillic reply came out garbled), used the wrong unescape order, and
 * could latch onto a {@code content} field belonging to an unrelated tool-call event.
 *
 * <p>When {@code --output-format json} is used, a real JSON parser walks the document for the
 * reply text under the field names qwen/gemini-family CLIs use ({@code response}, then common
 * fallbacks). When JSON parsing fails or finds nothing — e.g. plain-text mode — the raw output is
 * returned trimmed. Decoding of {@code \\uXXXX} and escape sequences is handled by Jackson, so
 * non-ASCII text is preserved correctly.
 */
public final class CliResponseParser {

    private static final Logger log = LoggerFactory.getLogger(CliResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Field names that may carry the assistant text, in priority order. {@code result} is qwen-code's
     * canonical final-answer field on its trailing {@code {"type":"result"}} event; {@code content}
     * matches the assistant message blocks (a {@code thinking} block has no {@code text}/{@code result}
     * field, so chain-of-thought is never surfaced).
     */
    private static final String[] TEXT_FIELDS =
            {"result", "response", "text", "content", "output", "message"};

    private CliResponseParser() {
    }

    /**
     * @param raw      the CLI's stdout
     * @param jsonMode whether the CLI was invoked with a JSON output format
     * @return the assistant reply, or an empty string if none could be extracted
     */
    public static String parse(String raw, boolean jsonMode) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!jsonMode) {
            return trimmed;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            String text = extract(root);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
            log.warn("JSON output had no recognizable text field; returning raw output");
        } catch (Exception e) {
            log.warn("CLI output was not valid JSON; returning raw output: {}", e.getMessage());
        }
        return trimmed;
    }

    private static String extract(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        // Object: prefer a known text field; "message" may itself be an object with "content".
        if (root.isObject()) {
            for (String field : TEXT_FIELDS) {
                JsonNode n = root.get(field);
                if (n == null) {
                    continue;
                }
                if (n.isTextual() && !n.asText().isEmpty()) {
                    return n.asText();
                }
                if (n.isObject() || n.isArray()) {
                    String nested = extract(n);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }
        // Array of events/messages: take the last non-empty assistant-like text.
        if (root.isArray()) {
            String last = null;
            for (Iterator<JsonNode> it = root.elements(); it.hasNext(); ) {
                String t = extract(it.next());
                if (t != null && !t.trim().isEmpty()) {
                    last = t;
                }
            }
            return last;
        }
        if (root.isTextual()) {
            return root.asText();
        }
        return null;
    }
}
