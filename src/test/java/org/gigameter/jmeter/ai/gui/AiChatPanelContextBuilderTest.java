package org.gigameter.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiChatPanelContextBuilderTest {

    @Test
    void includesScriptBodyForSelectedJsr223Element() {
        String context = AiChatPanel.buildSelectedElementContextForTest(
                "JSR223Sampler",
                "Prepare token",
                "Thread Group > Prepare token",
                "groovy",
                "vars.put(\"token\", \"123\")",
                500);

        assertTrue(context.contains("JSR223Sampler"));
        assertTrue(context.contains("groovy"));
        assertTrue(context.contains("vars.put(\"token\", \"123\")"));
    }

    @Test
    void includesTreePathInContext() {
        String context = AiChatPanel.buildSelectedElementContextForTest(
                "JSR223Sampler",
                "Prepare token",
                "Thread Group > Prepare token",
                "groovy",
                "log.info(\"hello\")",
                500);

        assertTrue(context.contains("Thread Group > Prepare token"));
    }

    @Test
    void truncatesLongScript() {
        String longScript = "x".repeat(600);
        String context = AiChatPanel.buildSelectedElementContextForTest(
                "JSR223Sampler",
                "Big script",
                "TG > Big script",
                "groovy",
                longScript,
                500);

        assertTrue(context.contains("...(truncated)"));
        assertFalse(context.contains(longScript));
    }
}
