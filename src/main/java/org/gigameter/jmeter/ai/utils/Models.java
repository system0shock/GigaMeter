package org.gigameter.jmeter.ai.utils;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Models {
    private static final Logger log = LoggerFactory.getLogger(Models.class);

    /**
     * Get OpenAI models as ModelListPage.
     */
    public static com.openai.models.ModelListPage getOpenAiModels(OpenAIClient client) {
        try {
            log.info("Fetching available models from OpenAI API");
            client = OpenAIOkHttpClient.builder()
                    .apiKey(AiConfig.getProperty("openai.api.key", "YOUR_API_KEY"))
                    .build();

            com.openai.models.ModelListPage models = client.models().list();

            log.info("Successfully retrieved {} models from OpenAI API", models.data().size());
            for (Model model : models.data()) {
                log.debug("Available OpenAI model: {}", model.id());
            }
            return models;
        } catch (Exception e) {
            log.error("Error fetching models from OpenAI API: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get OpenAI chat model IDs as a List of Strings.
     */
    public static List<String> getOpenAiModelIds(OpenAIClient client) {
        com.openai.models.ModelListPage models = getOpenAiModels(client);
        if (models != null && models.data() != null) {
            return models.data().stream()
                    .filter(model -> model.id().startsWith("gpt"))
                    .filter(model -> !model.id().contains("audio"))
                    .filter(model -> !model.id().contains("tts"))
                    .filter(model -> !model.id().contains("whisper"))
                    .filter(model -> !model.id().contains("davinci"))
                    .filter(model -> !model.id().contains("search"))
                    .filter(model -> !model.id().contains("transcribe"))
                    .filter(model -> !model.id().contains("realtime"))
                    .filter(model -> !model.id().contains("instruct"))
                    .map(com.openai.models.Model::id)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
