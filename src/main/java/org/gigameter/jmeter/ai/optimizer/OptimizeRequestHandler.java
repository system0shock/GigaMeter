package org.gigameter.jmeter.ai.optimizer;

import org.gigameter.jmeter.ai.utils.JMeterElementManager;
import org.gigameter.jmeter.ai.service.AiService;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.PropertyIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles requests related to optimizing JMeter elements.
 */
public class OptimizeRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(OptimizeRequestHandler.class);

    // Pattern to match requests to optimize elements - very inclusive to catch all
    // variations
    private static final Pattern OPTIMIZE_ELEMENT_PATTERN = Pattern.compile(
            "(?i).*\\b(optimize|improve|enhance)\\b.*");

    // No static AI service field needed as it will be passed as a parameter

    /**
     * Processes a user message to determine if it's requesting to optimize the
     * selected element.
     * 
     * @param userMessage The user's message
     * @return A response message, or null if the message is not a request to
     *         optimize an element
     */
    public static String processOptimizeTestPlanRequest(String userMessage) {
        if (userMessage == null) {
            return null;
        }

        // Log the incoming message for debugging
        log.info("OptimizeRequestHandler received message: '{}'", userMessage);

        // Special case for just "optimize"
        if (userMessage.trim().equalsIgnoreCase("optimize")) {
            log.info("Detected simple 'optimize' command");
            // Skip pattern matching and go straight to processing
        } else {
            // Define patterns to match requests to optimize the selected element
            Matcher matcher = OPTIMIZE_ELEMENT_PATTERN.matcher(userMessage);

            boolean matches = matcher.find();
            log.info("Message '{}' matches pattern: {}", userMessage, matches);

            if (!matches) {
                return null;
            }
        }

        log.info("Detected request to optimize selected element");

        // Check if test plan is ready
        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            return "I couldn't optimize the element because " + status.getErrorMessage().toLowerCase() +
                    ". Please make sure you have a test plan open.";
        }

        // Check if GuiPackage is available
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot optimize element");
            return "I couldn't optimize the element because the JMeter GUI is not available.";
        }

        // Check if the tree model is available
        if (guiPackage.getTreeModel() == null) {
            log.error("Tree model is null, cannot optimize element");
            return "I couldn't optimize the element because the test plan structure is not available.";
        }

        // We need an AI service to analyze the element, but we don't have one here
        // The caller should use the analyzeAndOptimizeSelectedElement method directly
        return "Please use the @optimize command in the chat panel to get optimization suggestions.";
    }

    /**
     * Analyzes the currently selected element and provides optimization
     * suggestions.
     * 
     * @param aiService The AI service to use for generating optimization suggestions
     * @return A response message with optimization suggestions
     */
    public static String analyzeAndOptimizeSelectedElement(AiService aiService) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot optimize selected element");
            return "I couldn't optimize because the JMeter GUI is not available.";
        }

        // Get the currently selected node
        JMeterTreeNode selectedNode = guiPackage.getTreeListener().getCurrentNode();
        if (selectedNode == null) {
            log.error("No element is currently selected");
            return "I couldn't optimize because no element is currently selected. Please select an element in the test plan.";
        }

        // Get the test element
        TestElement element = selectedNode.getTestElement();
        if (element == null) {
            log.error("Selected node has no test element");
            return "I couldn't optimize because the selected element is not valid.";
        }

        log.info("Starting optimization analysis for selected element: " + element.getName());

        // We now expect the AiService to be passed in from the caller
        if (aiService == null) {
            log.error("AI service not initialized");
            return "I couldn't optimize because the AI service is not available. Please try again later.";
        }

        try {
            // Get element type and name
            String elementType = element.getClass().getSimpleName();
            String elementName = element.getName();

            // Create a prompt for this specific element
            StringBuilder elementPrompt = new StringBuilder();
            elementPrompt.append("Проанализируй элемент JMeter и дай конкретные рекомендации по оптимизации:\n\n");
            elementPrompt.append("Тип: ").append(elementType).append("\n");
            elementPrompt.append("Имя: ").append(elementName).append("\n\n");

            // Add element properties
            elementPrompt.append("Свойства:\n");
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                org.apache.jmeter.testelement.property.JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();

                // Skip internal properties and empty values
                if (!propertyName.startsWith("TestElement.") && !propertyValue.isEmpty()) {
                    elementPrompt.append("- ").append(propertyName).append(": ").append(propertyValue).append("\n");
                }
            }

            // Add specific guidance based on element type
            elementPrompt.append("\nДай рекомендации для ").append(elementType).append(", акцент на:\n");

            if (elementType.contains("HTTPSampler")) {
                elementPrompt.append("- Настройки соединения и таймаутов\n")
                        .append("- Использование пула соединений\n")
                        .append("- Управление заголовками\n")
                        .append("- Эффективная передача параметров\n")
                        .append("- Настройки кодировки\n")
                        .append("- Обработка редиректов\n");
            } else if (elementType.contains("ThreadGroup")) {
                elementPrompt.append("- Количество потоков и ramp-up\n")
                        .append("- Настройка loop count\n")
                        .append("- Параметры планировщика\n")
                        .append("- Задержка старта потоков\n");
            } else if (elementType.contains("Timer")) {
                elementPrompt.append("- Адекватные значения задержки\n")
                        .append("- Влияние на пропускную способность теста\n")
                        .append("- Реалистичность имитации поведения пользователей\n");
            } else if (elementType.contains("Assertion")) {
                elementPrompt.append("- Область применения и поля assertion\n")
                        .append("- Эффективность сопоставления паттернов\n")
                        .append("- Влияние на производительность теста\n");
            } else if (elementType.contains("Extractor") || elementType.contains("PostProcessor")) {
                elementPrompt.append("- Эффективность извлечения данных\n")
                        .append("- Оптимизация регулярных выражений\n")
                        .append("- Соглашения по именованию переменных\n")
                        .append("- Обработка ошибок\n");
            } else if (elementType.contains("ConfigElement") || elementType.contains("Config")) {
                elementPrompt.append("- Корректная конфигурация под требования теста\n")
                        .append("- Возможность переиспользования в тест-плане\n")
                        .append("- Влияние на производительность\n");
            } else if (elementType.contains("Controller")) {
                elementPrompt.append("- Эффективность логики выполнения\n")
                        .append("- Глубина вложенности\n")
                        .append("- Читаемость и сопровождаемость теста\n");
            } else {
                elementPrompt.append("- Влияние на производительность\n")
                        .append("- Лучшие практики конфигурации\n")
                        .append("- Взаимодействие с другими элементами\n");
            }

            elementPrompt.append("\nДай 3–5 конкретных практических рекомендаций.");

            log.info("Sending selected element to AI for analysis");

            // Get AI recommendations for this element
            String elementRecommendations = aiService.generateResponse(java.util.Collections.singletonList(elementPrompt.toString()));

            // Format the response
            StringBuilder report = new StringBuilder();
            report.append("# Рекомендации по оптимизации: ").append(elementName)
                    .append(" (").append(elementType).append(")\n\n");
            report.append(elementRecommendations);

            log.info("Completed optimization analysis for selected element");
            return report.toString();

        } catch (Exception e) {
            log.error("Error getting AI recommendations for selected element", e);
            return "I encountered an error while analyzing the selected element: " + e.getMessage();
        }
    }
}

