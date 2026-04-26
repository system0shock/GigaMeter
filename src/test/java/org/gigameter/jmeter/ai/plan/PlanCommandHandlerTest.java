package org.gigameter.jmeter.ai.plan;

import org.gigameter.jmeter.ai.service.AiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanCommandHandlerTest {
    private static final String PREVIEW_FAILURE_PREFIX = "предпросмотр плана";

    @BeforeEach
    void resetPlanState() {
        PlanDraftStore.save(null, null);
        PlanApplyUndoStore.clear();
    }

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
    void returnsHintWhenAnalyzeCalledWithoutGui() {
        AiService aiService = mock(AiService.class);
        PlanCommandHandler handler = new PlanCommandHandler(aiService);

        String response = handler.processPlanCommand("@plan analyze");

        assertTrue(response.contains("анализ плана выполнить нельзя") || response.contains("не найдено"));
    }

    @Test
    void undoPlanApplyWithoutStoredOperation() {
        String response = PlanCommandHandler.undoLastAppliedPlan();
        assertTrue(response.contains("cannot rollback") || response.contains("Nothing"));
    }

    @Test
    void buildsPreviewFromValidJson() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":10,\"ramp_up_seconds\":5,\"duration_seconds\":60},\n" +
                "\"defaults\":{\"base_url\":\"https://api.example.com\"},\n" +
                "\"steps\":[{\"name\":\"Login\",\"sampler_type\":\"http\",\"method\":\"POST\",\"path\":\"/auth/login\",\"assert\":{\"status_code\":200}}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains("API TG"));
        assertTrue(response.contains("POST /auth/login"));
        assertTrue(response.contains("https://api.example.com"));
    }

    @Test
    void buildsPreviewWithCsvAndThinkTime() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":5,\"ramp_up_seconds\":5,\"duration_seconds\":120},\n" +
                "\"defaults\":{\"base_url\":\"https://api.example.com\",\"think_time_ms\":700,\"csv\":{\"file\":\"data/users.csv\",\"variables\":[\"username\",\"password\"]}},\n" +
                "\"steps\":[{\"name\":\"Login\",\"sampler_type\":\"http\",\"method\":\"POST\",\"path\":\"/auth/login\",\"query\":{\"tenant\":\"demo\"},\"body\":{\"username\":\"${username}\"},\"think_time_ms\":500,\"headers\":{\"Content-Type\":\"application/json\"}}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains("data/users.csv"));
        assertTrue(response.contains("username,password"));
        assertTrue(response.contains("700"));
        assertTrue(response.contains("Query"));
        assertTrue(response.contains("500"));
    }

    @Test
    void buildsPreviewWithJsr223SamplerAndProcessors() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":3,\"ramp_up_seconds\":3,\"duration_seconds\":30},\n" +
                "\"steps\":[{\n" +
                "\"name\":\"Generate vars\",\n" +
                "\"sampler_type\":\"jsr223\",\n" +
                "\"script_language\":\"groovy\",\n" +
                "\"script\":\"vars.put(\\\"token\\\",\\\"abc\\\")\",\n" +
                "\"pre_processors\":[{\"type\":\"jsr223\",\"name\":\"Prepare\",\"script_language\":\"groovy\",\"script\":\"vars.put(\\\"ts\\\",\\\"1\\\")\"}],\n" +
                "\"post_processors\":[{\"type\":\"jsr223\",\"name\":\"Extract\",\"script_language\":\"groovy\",\"script\":\"vars.put(\\\"status\\\",\\\"ok\\\")\"}]\n" +
                "}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan jsr223 flow");

        assertTrue(response.contains("JSR223/groovy"));
        assertTrue(response.contains("Pre-processor: 1"));
        assertTrue(response.contains("Post-processor: 1"));
    }

    @Test
    void returnsFailureHintWhenAiResponseIsEmpty() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("   ");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Пустой"));
        assertNull(PlanDraftStore.getLatestDraft());
    }

    @Test
    void returnsFailureHintWhenAiResponseIsMalformed() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("not json at all");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Некорректный"));
        assertNull(PlanDraftStore.getLatestDraft());
    }

    @Test
    void returnsFailureHintWhenAiServiceFails() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenThrow(new RuntimeException("401 unauthorized"));

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Сервис AI"));
        assertFalse(response.contains("Некорректный"));
        assertNull(PlanDraftStore.getLatestDraft());
    }

    @Test
    void returnsMalformedHintWhenDraftValidationFails() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\"},\n" +
                "\"steps\":[{\"name\":\"Login\",\"method\":\"POST\",\"path\":\"/auth/login\"}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Некорректный"));
    }

    @Test
    void clearsPreviousDraftWhenPreviewGenerationFails() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList()))
                .thenReturn("{\n" +
                        "\"thread_group\":{\"name\":\"API TG\",\"users\":10,\"ramp_up_seconds\":5,\"duration_seconds\":60},\n" +
                        "\"steps\":[{\"name\":\"Login\",\"sampler_type\":\"http\",\"method\":\"POST\",\"path\":\"/auth/login\"}]\n" +
                        "}")
                .thenReturn("   ");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String okResponse = handler.processPlanCommand("@plan login flow");
        String failResponse = handler.processPlanCommand("@plan broken flow");

        assertTrue(okResponse.contains("API TG"));
        assertTrue(failResponse.contains(PREVIEW_FAILURE_PREFIX));
        assertNull(PlanDraftStore.getLatestDraft());
        assertTrue(handler.processPlanCommand("@plan apply").contains("No plan draft found"));
    }

    @Test
    void returnsMalformedHintWhenHttpStepMissesRequiredFields() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":10,\"ramp_up_seconds\":5,\"duration_seconds\":60},\n" +
                "\"steps\":[{\"name\":\"Login\",\"sampler_type\":\"http\",\"method\":\"POST\"}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Некорректный"));
        assertNull(PlanDraftStore.getLatestDraft());
    }

    @Test
    void returnsMalformedHintWhenSamplerTypeIsMissing() {
        AiService aiService = mock(AiService.class);
        when(aiService.generateResponse(anyList())).thenReturn("{\n" +
                "\"thread_group\":{\"name\":\"API TG\",\"users\":10,\"ramp_up_seconds\":5,\"duration_seconds\":60},\n" +
                "\"steps\":[{\"name\":\"Login\",\"method\":\"POST\",\"path\":\"/auth/login\"}]\n" +
                "}");

        PlanCommandHandler handler = new PlanCommandHandler(aiService);
        String response = handler.processPlanCommand("@plan login flow");

        assertTrue(response.contains(PREVIEW_FAILURE_PREFIX));
        assertTrue(response.contains("Некорректный"));
        assertNull(PlanDraftStore.getLatestDraft());
    }
}
