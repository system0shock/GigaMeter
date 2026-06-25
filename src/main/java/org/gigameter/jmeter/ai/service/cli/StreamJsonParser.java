package org.gigameter.jmeter.ai.service.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decodes individual lines of qwen-code's {@code --output-format stream-json} output (JSONL, one
 * event object per line, Anthropic-style streaming envelopes). Stateless and line-oriented so the
 * transport can hand it lines as they arrive and the UI can render text incrementally.
 *
 * <p>Relevant event shapes:
 * <ul>
 *   <li>incremental text: {@code {"type":"stream_event","event":{"type":"content_block_delta",
 *       "delta":{"type":"text_delta","text":"…"}}}}</li>
 *   <li>final answer: {@code {"type":"result","result":"…","session_id":"…"}}</li>
 *   <li>every line carries {@code "session_id"}</li>
 * </ul>
 */
public final class StreamJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StreamJsonParser() {
    }

    /** Parses one JSONL line, or {@code null} if the line is blank or not valid JSON. */
    public static JsonNode parseLine(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** The incremental text chunk carried by a {@code content_block_delta}/{@code text_delta}, else null. */
    public static String textDelta(JsonNode line) {
        if (line == null) {
            return null;
        }
        JsonNode event = line.get("event");
        if (event == null || !"content_block_delta".equals(event.path("type").asText())) {
            return null;
        }
        JsonNode delta = event.get("delta");
        if (delta == null || !"text_delta".equals(delta.path("type").asText())) {
            return null;
        }
        JsonNode text = delta.get("text");
        return text != null && text.isTextual() ? text.asText() : null;
    }

    /** The canonical final answer from a {@code {"type":"result"}} event, else null. */
    public static String finalResult(JsonNode line) {
        if (line == null || !"result".equals(line.path("type").asText())) {
            return null;
        }
        JsonNode result = line.get("result");
        return result != null && result.isTextual() ? result.asText() : null;
    }

    /** The session id carried by the line, else null. */
    public static String sessionId(JsonNode line) {
        if (line == null) {
            return null;
        }
        JsonNode sid = line.get("session_id");
        return sid != null && sid.isTextual() ? sid.asText() : null;
    }
}
