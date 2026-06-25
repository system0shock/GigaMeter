package org.gigameter.jmeter.ai.service.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliResponseParserTest {

    @Test
    void decodesCyrillicEscapedAsUnicode() {
        // qwen --output-format json serializes non-ASCII as \\uXXXX (ensure_ascii)
        String raw = "{\"response\": \"\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442, JMeter\", \"stats\": {}}";
        String text = CliResponseParser.parse(raw, true);
        assertEquals("Привет, JMeter", text);
        assertFalse(text.contains("\\u04"), "must not leave literal \\uXXXX escapes");
    }

    @Test
    void decodesNewlinesInsideJsonString() {
        String raw = "{\"response\": \"line1\\nline2\"}";
        assertEquals("line1\nline2", CliResponseParser.parse(raw, true));
    }

    @Test
    void picksResponseFieldOverUnrelatedContent() {
        // A tool-call event may carry its own "content"; the reply lives under "response"
        String raw = "{\"events\":[{\"type\":\"tool\",\"content\":\"ls -la\"}],\"response\":\"Готово\"}";
        assertEquals("Готово", CliResponseParser.parse(raw, true));
    }

    @Test
    void walksArrayOfEventsForLastText() {
        String raw = "[{\"text\":\"first\"},{\"text\":\"second\"}]";
        assertEquals("second", CliResponseParser.parse(raw, true));
    }

    @Test
    void fallsBackToRawWhenNotJson() {
        String raw = "plain text answer, not json";
        assertEquals("plain text answer, not json", CliResponseParser.parse(raw, true));
    }

    @Test
    void plainTextModeReturnsTrimmedRaw() {
        assertEquals("hello", CliResponseParser.parse("  hello\n", false));
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertTrue(CliResponseParser.parse("", true).isEmpty());
        assertTrue(CliResponseParser.parse(null, true).isEmpty());
    }
}
