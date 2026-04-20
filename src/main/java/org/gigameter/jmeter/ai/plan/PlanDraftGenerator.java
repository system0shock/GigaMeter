package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gigameter.jmeter.ai.service.AiService;

import java.util.Collections;

final class PlanDraftGenerator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiService aiService;

    PlanDraftGenerator(AiService aiService) {
        this.aiService = aiService;
    }

    JsonNode generate(String scenario) throws Exception {
        String prompt = buildPrompt(scenario);
        String aiResponse = aiService.generateResponse(Collections.singletonList(prompt));
        return extractAndParseJson(aiResponse);
    }

    private String buildPrompt(String scenario) {
        return "Ты генерируешь черновик JMeter тест-плана для нагрузочного тестирования backend API.\n" +
                "Верни ТОЛЬКО валидный JSON без объяснений, комментариев и markdown-обёрток.\n\n" +
                "ОБЯЗАТЕЛЬНЫЕ поля (всегда заполняй):\n" +
                "- thread_group: name, users, ramp_up_seconds, duration_seconds\n" +
                "- steps[]: name, sampler_type (\"http\" или \"jsr223\")\n" +
                "- для http-шагов: method, path\n\n" +
                "ОПЦИОНАЛЬНЫЕ поля (включай только если явно нужны по сценарию):\n" +
                "- defaults.base_url — если указан хост\n" +
                "- defaults.think_time_ms — пауза между шагами по умолчанию\n" +
                "- defaults.csv — только если сценарий требует внешних тестовых данных\n" +
                "- steps[].headers — только нестандартные заголовки (Content-Type, Authorization и т.п.)\n" +
                "- steps[].body — только для POST/PUT/PATCH\n" +
                "- steps[].query — только если есть query-параметры\n" +
                "- steps[].assert.status_code — если сценарий подразумевает проверку кода ответа\n" +
                "- steps[].extract — только если токен или данные нужны в последующих шагах\n" +
                "- steps[].think_time_ms — если шаг требует паузы отличной от default\n" +
                "- steps[].pre_processors / post_processors — только для нетривиальной подготовки\n\n" +
                "Если в сценарии не указаны конкретные значения — используй разумные defaults:\n" +
                "users=10, ramp_up_seconds=30, duration_seconds=120, think_time_ms=500.\n\n" +
                "Структура JSON:\n" +
                "{\n" +
                "  \"thread_group\": {\"name\": \"string\", \"users\": number, \"ramp_up_seconds\": number, \"duration_seconds\": number},\n" +
                "  \"defaults\": {\"base_url\": \"string\", \"think_time_ms\": number},\n" +
                "  \"steps\": [\n" +
                "    {\"name\": \"string\", \"sampler_type\": \"http\", \"method\": \"GET\", \"path\": \"/path\",\n" +
                "     \"headers\": {}, \"body\": {}, \"query\": {}, \"assert\": {\"status_code\": 200},\n" +
                "     \"extract\": {\"var\": \"token\", \"json_path\": \"$.token\"}, \"think_time_ms\": number}\n" +
                "  ]\n" +
                "}\n\n" +
                "Сценарий:\n" + scenario;
    }

    private JsonNode extractAndParseJson(String response) throws Exception {
        if (response == null || response.trim().isEmpty()) {
            throw new IllegalStateException("Empty AI response");
        }

        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceAll("^```[a-zA-Z]*\\s*", "");
            normalized = normalized.replaceAll("\\s*```$", "");
        }

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("No JSON object found in AI response");
        }

        String json = normalized.substring(start, end + 1);
        return OBJECT_MAPPER.readTree(json);
    }
}
