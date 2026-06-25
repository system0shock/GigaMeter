package org.gigameter.jmeter.ai.service.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamJsonParserTest {

    @Test
    void extractsTextDelta() {
        String line = "{\"type\":\"stream_event\",\"session_id\":\"s1\",\"event\":{\"type\":\"content_block_delta\","
                + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Привет\"}}}";
        JsonNode node = StreamJsonParser.parseLine(line);
        assertEquals("Привет", StreamJsonParser.textDelta(node));
        assertEquals("s1", StreamJsonParser.sessionId(node));
        assertNull(StreamJsonParser.finalResult(node));
    }

    @Test
    void ignoresNonTextDeltas() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"}}}";
        assertNull(StreamJsonParser.textDelta(StreamJsonParser.parseLine(line)));
    }

    @Test
    void ignoresNonDeltaEvents() {
        String line = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_stop\"}}";
        assertNull(StreamJsonParser.textDelta(StreamJsonParser.parseLine(line)));
    }

    @Test
    void extractsFinalResult() {
        String line = "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s2\","
                + "\"result\":\"Привет! Чем помочь?\"}";
        JsonNode node = StreamJsonParser.parseLine(line);
        assertEquals("Привет! Чем помочь?", StreamJsonParser.finalResult(node));
        assertEquals("s2", StreamJsonParser.sessionId(node));
        assertNull(StreamJsonParser.textDelta(node));
    }

    @Test
    void extractsSessionIdFromInit() {
        String line = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc-123\"}";
        assertEquals("abc-123", StreamJsonParser.sessionId(StreamJsonParser.parseLine(line)));
    }

    @Test
    void blankAndMalformedLinesYieldNull() {
        assertNull(StreamJsonParser.parseLine(""));
        assertNull(StreamJsonParser.parseLine("   "));
        assertNull(StreamJsonParser.parseLine(null));
        assertNull(StreamJsonParser.parseLine("{not json"));
        assertNull(StreamJsonParser.textDelta(null));
        assertNull(StreamJsonParser.finalResult(null));
        assertNull(StreamJsonParser.sessionId(null));
    }

    @Test
    void reconstructsTextFromDeltaSequence() {
        String[] lines = {
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}",
            "{\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}}",
            "{\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}}",
            "{\"type\":\"result\",\"result\":\"Hello world\"}"
        };
        StringBuilder sb = new StringBuilder();
        String finalResult = null;
        for (String l : lines) {
            JsonNode node = StreamJsonParser.parseLine(l);
            String delta = StreamJsonParser.textDelta(node);
            if (delta != null) sb.append(delta);
            String fin = StreamJsonParser.finalResult(node);
            if (fin != null) finalResult = fin;
        }
        assertEquals("Hello world", sb.toString());
        assertEquals("Hello world", finalResult);
    }
}
