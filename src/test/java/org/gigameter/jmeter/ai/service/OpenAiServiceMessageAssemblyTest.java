package org.gigameter.jmeter.ai.service;

import com.openai.models.ChatCompletionCreateParams;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
