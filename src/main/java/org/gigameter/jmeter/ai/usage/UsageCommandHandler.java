package org.gigameter.jmeter.ai.usage;

import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.service.CliAiService;
import org.gigameter.jmeter.ai.service.GigaChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the @usage command to display token usage information.
 */
public class UsageCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UsageCommandHandler.class);

    public String processUsageCommand(AiService serviceToUse) {
        if (serviceToUse == null) {
            return "Сначала выберите модель.";
        }
        log.info("Processing usage command for service: {}", serviceToUse.getClass().getSimpleName());

        if (serviceToUse instanceof GigaChatService) {
            return GigaChatUsage.getInstance().getUsageSummary();
        } else if (serviceToUse instanceof CliAiService) {
            return "Отслеживание токенов недоступно для CLI-провайдеров (" +
                   serviceToUse.getName() + ").";
        } else {
            log.warn("Unknown service type: {}", serviceToUse.getClass().getSimpleName());
            return "Отслеживание использования не поддерживается для данного провайдера.";
        }
    }
}
