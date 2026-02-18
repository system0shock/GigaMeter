package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.utils.AiConfig;
import org.gigameter.jmeter.ai.usage.GigaChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GigaChatService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(GigaChatService.class);

    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "Вы — экспертный помощник по Apache JMeter в плагине GigaMeter. " +
                    "Отвечайте по-русски, кратко и практично. Помогайте создавать, оптимизировать и отлаживать тест-планы JMeter.";

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;

    private final String authUrl;
    private final String apiBaseUrl;
    private final String scope;
    private final String authKey;
    private final String staticAccessToken;
    private final boolean insecureTls;
    private final int timeoutMs;

    private volatile String cachedAccessToken;
    private volatile long cachedTokenExpiresAtEpochMs;

    public GigaChatService() {
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("giga.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("giga.default.model", "GigaChat");
        this.temperature = Float.parseFloat(AiConfig.getProperty("giga.temperature", "0.5"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("giga.max.tokens", "1024"));
        this.systemPrompt = AiConfig.getProperty("giga.system.prompt", DEFAULT_SYSTEM_PROMPT);

        this.authUrl = AiConfig.getProperty("giga.auth.url", "https://ngw.devices.sberbank.ru:9443/api/v2/oauth");
        this.apiBaseUrl = trimTrailingSlash(AiConfig.getProperty("giga.api.base.url",
                "https://gigachat.devices.sberbank.ru/api/v1"));
        this.scope = AiConfig.getProperty("giga.scope", "GIGACHAT_API_PERS");
        this.authKey = AiConfig.getProperty("giga.auth.key", "");
        this.staticAccessToken = AiConfig.getProperty("giga.access.token", "");
        this.insecureTls = Boolean.parseBoolean(AiConfig.getProperty("giga.ssl.insecure", "false"));
        this.timeoutMs = Integer.parseInt(AiConfig.getProperty("giga.timeout.ms", "30000"));
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
            String accessToken = getAccessToken();
            String payload = buildChatPayload(conversation);
            String response = httpRequest("POST", apiBaseUrl + "/chat/completions", payload,
                    "Bearer " + accessToken, "application/json");
            GigaChatUsage.getInstance().recordUsageFromResponse(response, currentModelId);

            String content = extractAssistantContent(response);
            if (content == null || content.isEmpty()) {
                return "Error: Empty response from GigaChat.";
            }
            return content;
        } catch (Exception e) {
            log.error("Error generating GigaChat response", e);
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
            String accessToken = getAccessToken();
            String response = httpRequest("GET", apiBaseUrl + "/models", null,
                    "Bearer " + accessToken, "application/json");

            Matcher matcher = MODEL_ID_PATTERN.matcher(response);
            while (matcher.find()) {
                modelIds.add(unescapeJson(matcher.group(1)));
            }
        } catch (Exception e) {
            log.warn("Failed to load GigaChat model list: {}", e.getMessage());
        }

        if (modelIds.isEmpty() && currentModelId != null && !currentModelId.isEmpty()) {
            modelIds.add(currentModelId);
        }
        return modelIds;
    }

    @Override
    public String getName() {
        return "Sber GigaChat";
    }

    private String buildChatPayload(List<String> conversation) {
        if (currentModelId == null || currentModelId.trim().isEmpty()) {
            currentModelId = "GigaChat";
        }

        float safeTemperature = temperature;
        if (safeTemperature < 0 || safeTemperature > 1) {
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

    private synchronized String getAccessToken() {
        if (staticAccessToken != null && !staticAccessToken.trim().isEmpty()) {
            return staticAccessToken.trim();
        }

        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < cachedTokenExpiresAtEpochMs - 60_000) {
            return cachedAccessToken;
        }

        if (authKey == null || authKey.trim().isEmpty()) {
            throw new IllegalStateException("Set giga.auth.key or giga.access.token in JMeter properties.");
        }

        String payload = "scope=" + escapeForm(scope);
        String response = httpRequest("POST", authUrl, payload,
                "Basic " + authKey.trim(), "application/x-www-form-urlencoded");

        String token = extractJsonField(response, "access_token");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("Could not parse access_token from GigaChat OAuth response.");
        }

        String expiresAt = extractJsonField(response, "expires_at");
        long expiresAtMs = now + (29L * 60L * 1000L);
        if (expiresAt != null && expiresAt.matches("\\d+")) {
            expiresAtMs = Long.parseLong(expiresAt);
        }

        cachedAccessToken = token;
        cachedTokenExpiresAtEpochMs = expiresAtMs;
        return cachedAccessToken;
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

            if (endpoint.contains("/oauth")) {
                connection.setRequestProperty("RqUID", UUID.randomUUID().toString());
            }

            if (connection instanceof HttpsURLConnection && insecureTls) {
                applyInsecureTls((HttpsURLConnection) connection);
            }

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

    private String escapeForm(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(" ", "%20");
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
            return "Unable to communicate with Sber GigaChat.";
        }
        if (message.contains("401")) {
            return "Authentication failed. Check giga.auth.key or giga.access.token.";
        }
        if (message.contains("403")) {
            return "Access denied by GigaChat API. Check scope and account permissions.";
        }
        if (message.contains("429")) {
            return "Rate limit exceeded. Retry later.";
        }
        return message;
    }

    private void applyInsecureTls(HttpsURLConnection connection) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            connection.setSSLSocketFactory(sc.getSocketFactory());
            connection.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception e) {
            log.warn("Failed to apply insecure TLS mode: {}", e.getMessage());
        }
    }
}

