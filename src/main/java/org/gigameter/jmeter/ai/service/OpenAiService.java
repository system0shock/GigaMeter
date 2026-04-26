package org.gigameter.jmeter.ai.service;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gigameter.jmeter.ai.utils.AiConfig;
import org.gigameter.jmeter.ai.usage.OpenAiUsage;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int DEFAULT_HISTORY_SIZE_FOR_TESTS = 10;
    private static final String DEFAULT_FALLBACK_USER_MESSAGE = "Hello, how can you help me with JMeter?";
    private final OpenAIClient client;
    private boolean systemPromptInitialized = false;

    private final int maxHistorySize;
    private String currentModelId;
    private float temperature;
    private String systemPrompt;
    private long maxTokens;
    // Default system prompt to focus responses on JMeter
    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "Вы — экспертный помощник по Apache JMeter в плагине GigaMeter. " +
            "Отвечайте по-русски, кратко и по делу. Помогайте создавать, анализировать, оптимизировать и отлаживать тест-планы JMeter. " +
            "Фокус: элементы JMeter, best practices, производительность, стабильность, читаемость, примеры Groovy/JSR223, " +
            "диагностика ошибок и практические шаги. Давайте точные рекомендации и готовые фрагменты, которые можно сразу применить.";

    public OpenAiService() {
        String API_KEY = AiConfig.getProperty("openai.api.key", "");
        String loggingLevel = AiConfig.getProperty("openai.log.level", "");
        if (!loggingLevel.isEmpty()) {
            // Set the environment variable for the OpenAI client logging
            System.setProperty("OPENAI_LOG", loggingLevel);
            log.info("Enabled OpenAI client logging with level: {}", loggingLevel);
        }
        this.client = new OpenAIOkHttpClient.Builder().apiKey(API_KEY).build();

        // Set the client in the OpenAiUsage singleton for token usage tracking
        try {
            OpenAiUsage.getInstance().setClient(this.client);
            log.info("Set OpenAI client in OpenAiUsage during initialization");
        } catch (Exception e) {
            log.error("Failed to set OpenAI client in OpenAiUsage", e);
        }

        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("openai.max.history.size", "10"));
        this.currentModelId = AiConfig.getProperty("openai.default.model", "gpt-4o");
        this.temperature = Float.parseFloat(AiConfig.getProperty("openai.temperature", "0.7"));
        this.systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
        this.maxTokens = Long.parseLong(AiConfig.getProperty("openai.max.tokens", "4096"));

        // Load system prompt from properties or use default
        try {
            systemPrompt = AiConfig.getProperty("openai.system.prompt", DEFAULT_JMETER_SYSTEM_PROMPT);

            if (systemPrompt == null) {
                log.warn("System prompt is null, using default");
                systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
            }

            log.info("Loaded system prompt from properties (length: {})", systemPrompt.length());
            log.info("System prompt initialized from properties: length={}", systemPrompt.length());
        } catch (Exception e) {
            log.error("Error loading system prompt, using default", e);
            systemPrompt = DEFAULT_JMETER_SYSTEM_PROMPT;
        }
    }

    public OpenAIClient getClient() {
        return client;
    }

    public void setModel(String modelId) {
        this.currentModelId = modelId;
        log.info("Model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return currentModelId;
    }

    public void setTemperature(float temperature) {
        if (temperature < 0 || temperature >= 1) {
            log.warn("Temperature must be between 0 and 1. Provided value: {}. Setting to default 0.7", temperature);
            this.temperature = 0.7f;
        } else {
            this.temperature = temperature;
            log.info("Temperature set to: {}", temperature);
        }
    }

    public float getTemperature() {
        return temperature;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
        log.info("Max tokens set to: {}", maxTokens);
    }

    public long getMaxTokens() {
        return maxTokens;
    }

    /**
     * Resets the system prompt initialization flag.
     * This should be called when starting a new conversation.
     */
    public void resetSystemPromptInitialization() {
        this.systemPromptInitialized = false;
        log.info("Reset system prompt initialization flag");
    }

    public String sendMessage(String message) {
        log.info("Sending message to OpenAI: length={}", message == null ? 0 : message.length());
        return generateResponse(java.util.Collections.singletonList(message));
    }

    public String generateResponse(List<String> conversation) {
        try {
            log.info("Generating response for conversation with {} messages", conversation.size());

            // Ensure a model is set
            if (currentModelId == null || currentModelId.isEmpty()) {
                currentModelId = "gpt-4o";
                log.warn("No model was set, defaulting to: {}", currentModelId);
            }

            // Ensure a temperature is set
            if (temperature < 0 || temperature > 1) {
                temperature = 0.7f;
                log.warn("Invalid temperature value ({}), defaulting to: {}", temperature, 0.7f);
            }

            // Log which model is being used for this conversation
            log.info("Generating response using model: {} and temperature: {}", currentModelId, temperature);

            // Check if this is the first message in a conversation based on
            // systemPromptInitialized flag
            boolean isFirstMessage = !systemPromptInitialized;
            if (isFirstMessage) {
                log.info("Using system prompt for first message");
                systemPromptInitialized = true;
            } else {
                log.info("Using previously initialized conversation with system prompt");
            }

            if (conversation.size() > maxHistorySize) {
                log.info("Limiting conversation to last {} messages", maxHistorySize);
            }
            ChatCompletionCreateParams params = buildParams(
                    systemPrompt,
                    currentModelId,
                    temperature,
                    maxTokens,
                    conversation,
                    maxHistorySize);
            log.info("Request parameters: maxTokens={}, temperature={}, model={}, messagesCount={}",
                    params.maxCompletionTokens(), params.temperature(), params.model(),
                    params.messages().size());

            ChatCompletion chatCompletion = client.chat().completions().create(params);

            log.info("Received OpenAI completion: id={}, model={}, choices={}",
                    chatCompletion.id(),
                    chatCompletion.model(),
                    chatCompletion.choices() == null ? 0 : chatCompletion.choices().size());

            // Record usage data if available
            try {
                OpenAiUsage.getInstance().recordUsage(chatCompletion, currentModelId);
                log.info("Recorded token usage for model: {}", currentModelId);
            } catch (Exception ex) {
                log.error("Failed to record token usage", ex);
            }

            // Extract the response content using SDK methods
            String responseContent;
            try {
                // Get the first choice
                ChatCompletion.Choice choice = chatCompletion.choices().get(0);

                // Extract the content from the message
                // The SDK provides methods to access the message and its content
                responseContent = choice.message().content().orElse("No content available");
            } catch (Exception ex) {
                log.error("Error extracting content using SDK methods", ex);

                // Fallback to using toString() if SDK methods fail
                String choiceStr = chatCompletion.choices().get(0).toString();

                // Extract just the actual content text
                int contentStart = choiceStr.indexOf("content=");
                if (contentStart > 0) {
                    contentStart += 8; // Move past "content="

                    // Find the end of the content (before refusal or annotations)
                    int contentEnd = choiceStr.indexOf(", refusal=", contentStart);
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf(", annotations=", contentStart);
                    }
                    if (contentEnd < 0) {
                        contentEnd = choiceStr.indexOf("}", contentStart);
                    }

                    if (contentEnd > contentStart) {
                        responseContent = choiceStr.substring(contentStart, contentEnd);
                    } else {
                        responseContent = choiceStr.substring(contentStart);
                    }
                } else {
                    responseContent = choiceStr;
                }
            }

            return responseContent;
        } catch (Exception e) {
            log.error("Error generating response", e);

            // Extract and format error message for better readability
            String errorMessage = extractUserFriendlyErrorMessage(e);
            return "Error: " + errorMessage;
        }
    }

    /**
     * Generates a response from the AI using the specified model.
     * 
     * @param conversation The conversation history
     * @param model        The specific model to use for this request
     * @return The AI's response
     */
    public String generateResponse(List<String> conversation, String model) {
        log.info("Generating response with specified model: {}", model);

        // Store current model
        String originalModel = this.currentModelId;

        try {
            // Set the specified model
            this.currentModelId = model;

            // Generate the response using the specified model
            return generateResponse(conversation);
        } finally {
            // Restore the original model
            this.currentModelId = originalModel;
            log.info("Restored original model: {}", originalModel);
        }
    }

    /**
     * Extracts a user-friendly error message from an exception
     * 
     * @param e The exception to extract the error message from
     * @return A user-friendly error message
     */
    private String extractUserFriendlyErrorMessage(Exception e) {
        String errorMessage = e.getMessage();

        // Check for credit balance error
        if (errorMessage != null && errorMessage.contains("insufficient_quota")) {
            return "Your credit balance is too low to access the OpenAI API. Please check your billing information.";
        }

        // Check for API key error
        if (errorMessage != null && errorMessage.contains("invalid_api_key")) {
            return "Invalid API key. Please check your API key and try again.";
        }

        // Check for rate limit error
        if (errorMessage != null && errorMessage.contains("rate_limit_exceeded")) {
            return "Rate limit exceeded. Please try again later.";
        }

        // Check for model not found error
        if (errorMessage != null && errorMessage.contains("model_not_found")) {
            return "The selected model was not found. Please select a different model.";
        }

        // Check for context length error
        if (errorMessage != null && errorMessage.contains("context_length_exceeded")) {
            return "The conversation is too long. Please start a new conversation.";
        }

        // For other errors, provide a cleaner message
        if (errorMessage != null) {
            // Try to extract a more readable message
            if (errorMessage.contains("OpenAIError")) {
                // Try to extract the message field from the error JSON
                int messageStart = errorMessage.indexOf("message=");
                if (messageStart != -1) {
                    int messageEnd = errorMessage.indexOf("}", messageStart);
                    if (messageEnd != -1) {
                        return errorMessage.substring(messageStart + 8, messageEnd);
                    }
                }
            }
        }

        // If we couldn't extract a specific error message, return a generic one
        return "An error occurred while communicating with the OpenAI API. Please try again later.";
    }

    public String getName() {
        return "OpenAI";
    }

    static ChatCompletionCreateParams buildParamsForTest(
            String systemPrompt,
            String model,
            float temperature,
            long maxTokens,
            List<String> conversation) {
        return buildParams(systemPrompt, model, temperature, maxTokens, conversation, DEFAULT_HISTORY_SIZE_FOR_TESTS);
    }

    private static ChatCompletionCreateParams buildParams(
            String systemPrompt,
            String model,
            float temperature,
            long maxTokens,
            List<String> conversation,
            int maxHistorySize) {
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(model)
                .temperature(temperature)
                .maxCompletionTokens(maxTokens)
                .addSystemMessage(systemPrompt);

        int historyStart = Math.max(conversation.size() - maxHistorySize, 0);
        List<String> limitedHistory = conversation.subList(historyStart, conversation.size());
        boolean addedConversationMessage = false;

        for (int i = 0; i < limitedHistory.size(); i++) {
            String msg = limitedHistory.get(i);
            if (msg == null || msg.isBlank()) {
                continue;
            }
            addedConversationMessage = true;
            if ((historyStart + i) % 2 == 0) {
                paramsBuilder.addUserMessage(msg);
            } else {
                paramsBuilder.addMessage(
                        com.openai.models.ChatCompletionAssistantMessageParam.builder()
                                .content(msg)
                                .build());
            }
        }
        if (!addedConversationMessage) {
            paramsBuilder.addUserMessage(DEFAULT_FALLBACK_USER_MESSAGE);
        }
        return paramsBuilder.build();
    }
}


