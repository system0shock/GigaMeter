package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeepSeekService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern OPENAI_ERROR_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "Вы — экспертный помощник по Apache JMeter в плагине GigaMeter. " +
                    "Отвечайте по-русски, кратко и по делу. Помогайте создавать, анализировать, " +
                    "оптимизировать и отлаживать тест-планы JMeter.";

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;

    private final String apiBaseUrl;
    private final String apiKey;
    private final int timeoutMs;

    public DeepSeekService() {
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("deepseek.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("deepseek.default.model", "deepseek-chat");
        this.temperature = Float.parseFloat(AiConfig.getProperty("deepseek.temperature", "0.5"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("deepseek.max.tokens", "1024"));
        this.systemPrompt = AiConfig.getProperty("deepseek.system.prompt", DEFAULT_SYSTEM_PROMPT);

        this.apiBaseUrl = trimTrailingSlash(AiConfig.getProperty("deepseek.api.base.url", "https://api.deepseek.com/v1"));
        this.apiKey = AiConfig.getProperty("deepseek.api.key", "");
        this.timeoutMs = Integer.parseInt(AiConfig.getProperty("deepseek.timeout.ms", "30000"));
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    @Override
    public String generateResponse(List<String> conversation) {
        try {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalStateException("Set deepseek.api.key in JMeter properties.");
            }

            String payload = buildChatPayload(conversation);
            String response = httpRequest("POST", apiBaseUrl + "/chat/completions", payload,
                    "Bearer " + apiKey.trim(), "application/json");

            String content = extractAssistantContent(response);
            if (content == null || content.isEmpty()) {
                return "Error: Empty response from DeepSeek.";
            }
            return content;
        } catch (Exception e) {
            log.error("Error generating DeepSeek response", e);
            return "Error: " + userFriendlyError(e);
        }
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        String originalModel = this.currentModelId;
        try {
            this.currentModelId = model;
            return generateResponse(conversation);
        } finally {
            this.currentModelId = originalModel;
        }
    }

    public List<String> getModelIds() {
        List<String> modelIds = new ArrayList<>();
        try {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                return modelIds;
            }

            String response = httpRequest("GET", apiBaseUrl + "/models", null,
                    "Bearer " + apiKey.trim(), "application/json");

            Matcher matcher = MODEL_ID_PATTERN.matcher(response);
            while (matcher.find()) {
                String id = unescapeJson(matcher.group(1));
                if (id != null && !id.isEmpty()) {
                    modelIds.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load DeepSeek model list: {}", e.getMessage());
        }

        if (modelIds.isEmpty() && currentModelId != null && !currentModelId.isEmpty()) {
            modelIds.add(currentModelId);
        }
        return modelIds;
    }

    @Override
    public String getName() {
        return "DeepSeek";
    }

    private String buildChatPayload(List<String> conversation) {
        if (currentModelId == null || currentModelId.trim().isEmpty()) {
            currentModelId = "deepseek-chat";
        }

        float safeTemperature = temperature;
        if (safeTemperature < 0 || safeTemperature > 2) {
            safeTemperature = 0.5f;
        }

        List<String> history = conversation == null ? new ArrayList<>() : conversation;
        if (history.size() > maxHistorySize) {
            history = history.subList(history.size() - maxHistorySize, history.size());
        }

        StringBuilder messages = new StringBuilder();
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(escapeJson(systemPrompt))
                .append("\"}");

        for (int i = 0; i < history.size(); i++) {
            String msg = history.get(i);
            if (msg == null || msg.trim().isEmpty()) {
                continue;
            }
            String role = (i % 2 == 0) ? "user" : "assistant";
            messages.append(",{\"role\":\"")
                    .append(role)
                    .append("\",\"content\":\"")
                    .append(escapeJson(msg))
                    .append("\"}");
        }

        return "{"
                + "\"model\":\"" + escapeJson(currentModelId) + "\","
                + "\"temperature\":" + safeTemperature + ","
                + "\"max_tokens\":" + maxTokens + ","
                + "\"messages\":[" + messages + "]"
                + "}";
    }

    private String httpRequest(String method, String endpoint, String body, String authorization, String contentType) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Content-Type", contentType);

            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readBody(
                    responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (responseCode >= 400) {
                String apiMessage = extractOpenAiStyleError(responseBody);
                if (apiMessage != null && !apiMessage.isEmpty()) {
                    throw new IllegalStateException("HTTP " + responseCode + ": " + apiMessage);
                }
                throw new IllegalStateException("HTTP " + responseCode + ": " + responseBody);
            }

            return responseBody;
        } catch (Exception e) {
            throw new IllegalStateException("Request failed for " + endpoint + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readBody(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String extractAssistantContent(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        int messageIndex = json.indexOf("\"message\"");
        String searchSpace = messageIndex >= 0 ? json.substring(messageIndex) : json;

        Matcher matcher = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(searchSpace);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

    private String extractOpenAiStyleError(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Matcher matcher = OPENAI_ERROR_PATTERN.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return null;
    }

    private String extractJsonField(String json, String field) {
        Matcher matcher = Pattern.compile(String.format(JSON_STRING_FIELD.pattern(), Pattern.quote(field))).matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }

        Matcher numericMatcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (numericMatcher.find()) {
            return numericMatcher.group(1);
        }
        return null;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String userFriendlyError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return "Unable to communicate with DeepSeek.";
        }
        if (message.contains("401")) {
            return "Authentication failed. Check deepseek.api.key.";
        }
        if (message.contains("403")) {
            return "Access denied by DeepSeek API.";
        }
        if (message.contains("429")) {
            return "Rate limit exceeded. Retry later.";
        }
        return message;
    }
}
