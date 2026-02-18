package org.gigameter.jmeter.ai.usage;

import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.service.DeepSeekService;
import org.gigameter.jmeter.ai.service.GigaChatService;
import org.gigameter.jmeter.ai.service.OpenAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            return "Please select a model first.";
        }
        log.info("Processing usage command for service: {}", serviceToUse.getClass().getSimpleName());

        if (serviceToUse instanceof OpenAiService) {
            log.info("Processing OpenAI usage request");

            OpenAiService openAiService = (OpenAiService) serviceToUse;
            try {
                OpenAiUsage.getInstance().setClient(openAiService.getClient());
                log.info("Set OpenAI client in OpenAiUsage from OpenAiService");
            } catch (Exception e) {
                log.error("Failed to set OpenAI client in OpenAiUsage", e);
            }

            return OpenAiUsage.getInstance().getUsageSummary();
        } else if (serviceToUse instanceof GigaChatService) {
            return GigaChatUsage.getInstance().getUsageSummary();
        } else if (serviceToUse instanceof DeepSeekService) {
            return "DeepSeek usage tracking is not implemented yet.";
        } else {
            log.warn("Unknown service type: {}", serviceToUse.getClass().getSimpleName());
            return "Usage tracking is not supported for this provider.";
        }
    }
}
