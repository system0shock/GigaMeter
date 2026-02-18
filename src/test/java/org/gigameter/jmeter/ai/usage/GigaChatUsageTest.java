package org.gigameter.jmeter.ai.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GigaChatUsageTest {

    @Test
    void recordsUsageFromSnakeCaseFields() {
        GigaChatUsage usage = GigaChatUsage.getInstance();
        String response = "{\n" +
                "  \"usage\": {\n" +
                "    \"prompt_tokens\": 12,\n" +
                "    \"completion_tokens\": 8,\n" +
                "    \"total_tokens\": 20\n" +
                "  }\n" +
                "}";

        usage.recordUsageFromResponse(response, "GigaChat");
        String summary = usage.getUsageSummary();

        assertTrue(summary.contains("Sber GigaChat Usage Summary"));
        assertTrue(summary.contains("20"));
    }
}
