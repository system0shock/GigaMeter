package org.gigameter.jmeter.ai.service;

import com.openai.models.ChatCompletionCreateParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiServiceMessageAssemblyTest {

    @Test
    void buildsConversationWithAssistantRoleInsteadOfSystemRole() {
        ChatCompletionCreateParams params = OpenAiService.buildParamsForTest(
                "demo-system",
                "gpt-4o",
                0.7f,
                512,
                List.of("user-1", "assistant-1", "user-2"));

        assertSystemMessage(params, 0, "demo-system");
        assertUserMessage(params, 1, "user-1");
        assertAssistantMessage(params, 2, "assistant-1");
        assertUserMessage(params, 3, "user-2");
    }

    @Test
    void trimsHistoryOnUserTurnInsteadOfKeepingOrphanedAssistantReply() {
        ChatCompletionCreateParams params = OpenAiService.buildParamsForTest(
                "demo-system",
                "gpt-4o",
                0.7f,
                512,
                List.of(
                        "user-0",
                        "assistant-0",
                        "user-1",
                        "assistant-1",
                        "user-2",
                        "assistant-2",
                        "user-3",
                        "assistant-3",
                        "user-4",
                        "assistant-4",
                        "user-5"));

        assertEquals(10, params.messages().size());
        assertSystemMessage(params, 0, "demo-system");
        assertUserMessage(params, 1, "user-1");
        assertAssistantMessage(params, 2, "assistant-1");
        assertUserMessage(params, 3, "user-2");
        assertAssistantMessage(params, 4, "assistant-2");
        assertUserMessage(params, 5, "user-3");
        assertAssistantMessage(params, 6, "assistant-3");
        assertUserMessage(params, 7, "user-4");
        assertAssistantMessage(params, 8, "assistant-4");
        assertUserMessage(params, 9, "user-5");
    }

    @Test
    void addsFallbackUserMessageWhenHistoryIsEmptyOrBlank() {
        ChatCompletionCreateParams params = OpenAiService.buildParamsForTest(
                "demo-system",
                "gpt-4o",
                0.7f,
                512,
                List.of("", "   "));

        assertEquals(2, params.messages().size());
        assertSystemMessage(params, 0, "demo-system");
        assertUserMessage(params, 1, "Hello, how can you help me with JMeter?");
    }

    private static void assertSystemMessage(ChatCompletionCreateParams params, int index, String expectedContent) {
        assertTrue(params.messages().get(index).isSystem());
        assertEquals(expectedContent, params.messages().get(index).asSystem().content().asText());
    }

    private static void assertUserMessage(ChatCompletionCreateParams params, int index, String expectedContent) {
        assertTrue(params.messages().get(index).isUser());
        assertEquals(expectedContent, params.messages().get(index).asUser().content().asText());
    }

    private static void assertAssistantMessage(ChatCompletionCreateParams params, int index, String expectedContent) {
        assertTrue(params.messages().get(index).isAssistant());
        assertEquals(expectedContent, params.messages().get(index).asAssistant().content().orElseThrow().asText());
    }
}
