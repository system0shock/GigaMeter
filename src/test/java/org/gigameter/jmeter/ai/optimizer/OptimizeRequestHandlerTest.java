package org.gigameter.jmeter.ai.optimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizeRequestHandlerTest {

    @Test
    void wrapsOptimizePromptWithStableOutputInstructions() {
        String prompt = OptimizeRequestHandler.buildPromptForTest(
                "HTTPSamplerProxy",
                "Login request",
                "- domain: api.example.com\n- method: POST\n");

        assertTrue(prompt.contains("Формат:"));
        assertTrue(prompt.contains("1. Summary"));
        assertTrue(prompt.contains("2. Risks"));
        assertTrue(prompt.contains("3. Recommendations"));
    }

    @Test
    void includesElementTypeAndNameInPrompt() {
        String prompt = OptimizeRequestHandler.buildPromptForTest(
                "HTTPSamplerProxy",
                "Login request",
                "- domain: api.example.com\n");

        assertTrue(prompt.contains("HTTPSamplerProxy"));
        assertTrue(prompt.contains("Login request"));
        assertTrue(prompt.contains("api.example.com"));
    }
}
