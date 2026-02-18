package org.gigameter.jmeter.ai.plan;

import org.gigameter.jmeter.ai.service.AiService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanCommandHandlerTest {

    @Test
    void returnsUsageMessageWhenScenarioIsMissing() {
        AiService aiService = mock(AiService.class);
        PlanCommandHandler handler = new PlanCommandHandler(aiService);

        String response = handler.processPlanCommand("@plan");

        assertTrue(response.startsWith("Usage: @plan"));
    }

    @Test
    void returnsHintWhenApplyCalledWithoutDraft() {
        AiService aiService = mock(AiService.class);
        PlanCommandHandler handler = new PlanCommandHandler(aiService);

        String response = handler.processPlanCommand("@plan apply");

        assertTrue(response.contains("No plan draft found"));
    }

    @Test
    void buildsPreviewFromValidJson() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":10,\"ramp_up_seconds\":5,\"duration_seconds\":60},\n" +
                "\"defaults\":{\"base_url\":\"https://api.example.com\"},\n" +
                "\"steps\":[{\"name\":\"Login\",\"method\":\"POST\",\"path\":\"/auth/login\",\"assert\":{\"status_code\":200}}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains("AI Plan Preview"));
        assertTrue(response.contains("API TG"));
        assertTrue(response.contains("POST /auth/login"));
    }
}
