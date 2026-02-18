package org.gigameter.jmeter.ai.usage;

import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.service.OpenAiService;
import org.gigameter.jmeter.ai.service.ClaudeService;
import org.gigameter.jmeter.ai.service.GigaChatService;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Handles the @usage command to display token usage information.
 */
public class UsageCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UsageCommandHandler.class);

    /**
     * Process the usage command based on the AI service being used.
     *
     * @param serviceToUse The AI service to get usage information from
     * @return A formatted string containing usage information
     */
    public String processUsageCommand(AiService serviceToUse) {
        if (serviceToUse == null) {
            return "РЎРЅР°С‡Р°Р»Р° РІС‹Р±РµСЂРёС‚Рµ РјРѕРґРµР»СЊ.";
        }
        log.info("Processing usage command for service: {}", serviceToUse.getClass().getSimpleName());

        // Check if the service is OpenAI
        if (serviceToUse instanceof OpenAiService) {
            log.info("Processing OpenAI usage request");

            // Get the OpenAI client from the service and set it in the OpenAiUsage instance
            OpenAiService openAiService = (OpenAiService) serviceToUse;
            try {
                // Set the OpenAI client in the OpenAiUsage instance
                OpenAiUsage.getInstance().setClient(openAiService.getClient());
                log.info("Set OpenAI client in OpenAiUsage from OpenAiService");
            } catch (Exception e) {
                log.error("Failed to set OpenAI client in OpenAiUsage", e);
            }

            // Return the usage summary
            return OpenAiUsage.getInstance().getUsageSummary();
        } else if (serviceToUse instanceof ClaudeService) {
            log.info("Processing Anthropic usage request");

            // Get the Anthropic client from the service and set it in the AnthropicUsage
            // instance
            ClaudeService claudeService = (ClaudeService) serviceToUse;
            try {
                // Set the Anthropic client in the AnthropicUsage instance
                AnthropicUsage.getInstance().setClient(claudeService.getClient());
                log.info("Set Anthropic client in AnthropicUsage from ClaudeService");
            } catch (Exception e) {
                log.error("Failed to set Anthropic client in AnthropicUsage", e);
            }

            // Return the usage summary
            return AnthropicUsage.getInstance().getUsageSummary();
        } else if (serviceToUse instanceof GigaChatService) {
            return "Р”Р»СЏ Sber GigaChat РїРѕРґСЃС‡С‘С‚ usage РїРѕРєР° РЅРµ СЂРµР°Р»РёР·РѕРІР°РЅ.";
        } else {
            // For unknown services
            log.warn("Unknown service type: {}", serviceToUse.getClass().getSimpleName());
            return "Р”Р»СЏ СЌС‚РѕРіРѕ С‚РёРїР° AI-СЃРµСЂРІРёСЃР° РїРѕРґСЃС‡С‘С‚ usage РЅРµ РїРѕРґРґРµСЂР¶РёРІР°РµС‚СЃСЏ.";
        }
    }
}

