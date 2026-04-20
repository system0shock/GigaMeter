package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

final class PlanPreviewRenderer {
    String render(JsonNode root, String scenario) {
        StringBuilder out = new StringBuilder();
        out.append("# Предпросмотр плана (черновик)\n\n");
        out.append("`Только предпросмотр` — изменения в тест-план ещё не применены.\n\n");
        out.append("## Сценарий\n");
        out.append(scenario).append("\n\n");

        JsonNode tg = root.path("thread_group");
        out.append("## Thread Group\n");
        out.append("- Название: ").append(textOrDefault(tg.path("name"), "API Users")).append("\n");
        out.append("- Пользователи: ").append(numberOrDefault(tg.path("users"), 1)).append("\n");
        out.append("- Ramp-up (с): ").append(numberOrDefault(tg.path("ramp_up_seconds"), 1)).append("\n");
        out.append("- Длительность (с): ").append(numberOrDefault(tg.path("duration_seconds"), 60)).append("\n\n");

        JsonNode defaults = root.path("defaults");
        if (defaults.isObject() && (defaults.has("base_url") || defaults.has("think_time_ms") || defaults.has("csv"))) {
            out.append("## Настройки по умолчанию\n");
            if (defaults.has("base_url")) {
                out.append("- Base URL: ").append(textOrDefault(defaults.path("base_url"), "")).append("\n");
            }
            if (defaults.has("think_time_ms")) {
                out.append("- Пауза между шагами (мс): ")
                        .append(numberOrDefault(defaults.path("think_time_ms"), 0)).append("\n");
            }
            JsonNode csv = defaults.path("csv");
            if (csv.isObject() && csv.has("file")) {
                out.append("- CSV файл: ").append(textOrDefault(csv.path("file"), "")).append("\n");
                if (csv.has("variables")) {
                    out.append("  - Переменные: ").append(joinCsvVariables(csv.path("variables"))).append("\n");
                }
            }
            out.append("\n");
        }

        out.append("## Шаги\n");
        JsonNode steps = root.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String name = textOrDefault(step.path("name"), "Шаг " + (i + 1));
            String samplerType = textOrDefault(step.path("sampler_type"), "http").toLowerCase();
            String method = textOrDefault(step.path("method"), "GET");
            String path = textOrDefault(step.path("path"), "/");
            if ("jsr223".equals(samplerType)) {
                out.append(i + 1).append(". ").append(name).append(" — JSR223/")
                        .append(textOrDefault(step.path("script_language"), "groovy")).append("\n");
            } else {
                out.append(i + 1).append(". ").append(name).append(" — ").append(method).append(" ").append(path).append("\n");
            }

            JsonNode assertion = step.path("assert");
            if (assertion.isObject() && assertion.has("status_code")) {
                out.append("   - Проверка статуса: ").append(numberOrDefault(assertion.path("status_code"), 200)).append("\n");
            }

            JsonNode extract = step.path("extract");
            if (extract.isObject() && extract.has("var") && extract.has("json_path")) {
                out.append("   - Извлечение: ").append(textOrDefault(extract.path("var"), "var"))
                        .append(" <= ").append(textOrDefault(extract.path("json_path"), "$")).append("\n");
            }

            JsonNode headers = step.path("headers");
            if (headers.isObject()) {
                int headerCount = 0;
                Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
                while (it.hasNext()) {
                    it.next();
                    headerCount++;
                }
                if (headerCount > 0) {
                    out.append("   - Заголовки: ").append(headerCount).append("\n");
                }
            }

            JsonNode query = step.path("query");
            if (query.isObject() && query.size() > 0) {
                out.append("   - Query-параметры: ").append(query.size()).append("\n");
            }

            JsonNode body = step.path("body");
            if (!body.isMissingNode() && !body.isNull()) {
                out.append("   - Тело запроса: есть\n");
            }

            int thinkTime = numberOrDefault(step.path("think_time_ms"), 0);
            if (thinkTime > 0) {
                out.append("   - Пауза (мс): ").append(thinkTime).append("\n");
            }

            JsonNode preProcessors = step.path("pre_processors");
            if (preProcessors.isArray() && preProcessors.size() > 0) {
                out.append("   - Pre-processor: ").append(preProcessors.size()).append("\n");
            }
            JsonNode postProcessors = step.path("post_processors");
            if (postProcessors.isArray() && postProcessors.size() > 0) {
                out.append("   - Post-processor: ").append(postProcessors.size()).append("\n");
            }
        }

        out.append("\n## Следующий шаг\n");
        out.append("Если предпросмотр выглядит верно — выполни `@plan apply` чтобы применить структуру в JMeter.");
        return out.toString();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isValueNode() ? node.asText() : fallback;
    }

    private int numberOrDefault(JsonNode node, int fallback) {
        return node != null && node.isNumber() ? node.asInt() : fallback;
    }

    private String joinCsvVariables(JsonNode variablesNode) {
        if (variablesNode == null || variablesNode.isMissingNode() || variablesNode.isNull()) {
            return "";
        }
        if (variablesNode.isTextual()) {
            return variablesNode.asText();
        }
        if (!variablesNode.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < variablesNode.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(variablesNode.get(i).asText());
        }
        return sb.toString();
    }
}
