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

    JsonNode generate(String scenario) throws PlanDraftException {
        String prompt = buildPrompt(scenario);
        String aiResponse;
        try {
            aiResponse = aiService.generateResponse(Collections.singletonList(prompt));
        } catch (Exception e) {
            throw PlanDraftException.serviceFailure(e);
        }
        return extractAndParseJson(aiResponse);
    }

    private String buildPrompt(String scenario) {
        return "Ты генерируешь черновик JMeter тест-плана для нагрузочного тестирования backend API.\n" +
                "Верни ТОЛЬКО валидный JSON без объяснений, комментариев и markdown-обёрток.\n\n" +
                "ПРАВИЛА (строго соблюдай):\n" +
                "1. Если сценарий включает аутентификацию — первым шагом ставь login, используй extract для токена,\n" +
                "   и передавай его через headers (Authorization: Bearer ${token}) во все последующие шаги.\n" +
                "2. Логически связанные шаги (например, оформление заказа) группируй в transaction_controller.\n" +
                "3. Для каждого HTTP-шага где ожидается конкретный ответ — добавляй assert.status_code.\n" +
                "4. POST/PUT/PATCH шаги должны иметь body и заголовок Content-Type в headers.\n" +
                "5. Если нужны тестовые данные (логины, ID и т.п.) — добавляй defaults.csv.\n" +
                "6. Defaults при отсутствии явных значений: users=10, ramp_up_seconds=30, duration_seconds=120, think_time_ms=1000.\n" +
                "7. Если шаг sampler_type=jsr223 — поле script ОБЯЗАТЕЛЬНО должно содержать реальный Groovy-код, не пустую строку.\n\n" +
                "ПРИМЕР — типовой auth + CRUD flow:\n" +
                "{\n" +
                "  \"thread_group\": {\"name\": \"API Users\", \"users\": 10, \"ramp_up_seconds\": 30, \"duration_seconds\": 120},\n" +
                "  \"defaults\": {\"base_url\": \"https://api.example.com\", \"think_time_ms\": 1000},\n" +
                "  \"steps\": [\n" +
                "    {\"name\": \"POST_Login\", \"sampler_type\": \"http\", \"method\": \"POST\", \"path\": \"/auth/login\",\n" +
                "     \"headers\": {\"Content-Type\": \"application/json\"},\n" +
                "     \"body\": {\"username\": \"${username}\", \"password\": \"${password}\"},\n" +
                "     \"assert\": {\"status_code\": 200},\n" +
                "     \"extract\": {\"var\": \"token\", \"json_path\": \"$.access_token\"}},\n" +
                "    {\"name\": \"GET_Orders\", \"sampler_type\": \"http\", \"method\": \"GET\", \"path\": \"/api/orders\",\n" +
                "     \"headers\": {\"Authorization\": \"Bearer ${token}\"},\n" +
                "     \"assert\": {\"status_code\": 200},\n" +
                "     \"think_time_ms\": 500},\n" +
                "    {\"name\": \"POST_CreateOrder\", \"sampler_type\": \"http\", \"method\": \"POST\", \"path\": \"/api/orders\",\n" +
                "     \"headers\": {\"Authorization\": \"Bearer ${token}\", \"Content-Type\": \"application/json\"},\n" +
                "     \"body\": {\"item\": \"widget\", \"qty\": 1},\n" +
                "     \"assert\": {\"status_code\": 201},\n" +
                "     \"extract\": {\"var\": \"orderId\", \"json_path\": \"$.id\"},\n" +
                "     \"think_time_ms\": 800},\n" +
                "    {\"name\": \"JSR_GenerateUUID\", \"sampler_type\": \"jsr223\",\n" +
                "     \"script_language\": \"groovy\",\n" +
                "     \"script\": \"vars.put('requestId', UUID.randomUUID().toString())\\nlog.info('Generated requestId: ' + vars.get('requestId'))\"}\n" +
                "  ]\n" +
                "}\n\n" +
                "ПОДДЕРЖИВАЕМЫЕ ПОЛЯ (включай только нужные):\n" +
                "- thread_group: name, users, ramp_up_seconds, duration_seconds\n" +
                "- defaults: base_url, think_time_ms, csv{file, variables[], delimiter}\n" +
                "- steps[]: name, sampler_type(\"http\"/\"jsr223\"), method, path,\n" +
                "  headers{}, body{}, query{}, assert{status_code},\n" +
                "  extract{var, json_path}, think_time_ms,\n" +
                "  transaction_controller (string — имя группировки),\n" +
                "  pre_processors[], post_processors[],\n" +
                "  script_language (для jsr223, default: groovy), script (обязательный код для jsr223)\n\n" +
                "Сценарий:\n" + scenario;
    }

    private JsonNode extractAndParseJson(String response) throws PlanDraftException {
        if (response == null || response.trim().isEmpty()) {
            throw PlanDraftException.emptyResponse();
        }

        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceAll("^```[a-zA-Z]*\\s*", "");
            normalized = normalized.replaceAll("\\s*```$", "");
        }

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw PlanDraftException.malformedResponse("No JSON object found in AI response", null);
        }

        String json = normalized.substring(start, end + 1);
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw PlanDraftException.malformedResponse("Failed to parse AI response as JSON", e);
        }
    }
}
