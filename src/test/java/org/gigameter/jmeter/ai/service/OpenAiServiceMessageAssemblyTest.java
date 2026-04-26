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

        assertTrue(params.messages().get(0).isSystem());
        assertTrue(params.messages().get(1).isUser());
        assertTrue(params.messages().get(2).isAssistant());
        assertTrue(params.messages().get(3).isUser());
    }

    @Test
    void preservesAssistantParityAfterHistoryIsTrimmed() {
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

        assertEquals(11, params.messages().size());
        assertTrue(params.messages().get(0).isSystem());
        assertTrue(params.messages().get(1).isAssistant());
        assertTrue(params.messages().get(2).isUser());
        assertTrue(params.messages().get(3).isAssistant());
        assertTrue(params.messages().get(4).isUser());
        assertTrue(params.messages().get(5).isAssistant());
        assertTrue(params.messages().get(6).isUser());
        assertTrue(params.messages().get(7).isAssistant());
        assertTrue(params.messages().get(8).isUser());
        assertTrue(params.messages().get(9).isAssistant());
        assertTrue(params.messages().get(10).isUser());
    }
}
