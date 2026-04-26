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

        JMeterTreeNode selectedNode = guiPackage.getTreeListener().getCurrentNode();
        if (selectedNode == null) {
            return "Не выбран элемент тест-плана. Выберите элемент и повторите команду.";
        }

        TestElement element = selectedNode.getTestElement();
        if (element == null) {
            return "Выбранный элемент недоступен.";
        }

        if (aiService == null) {
            return "AI сервис недоступен. Проверьте настройки подключения.";
        }

        log.info("Starting optimization analysis for: {}", element.getName());

        try {
            String elementType = element.getClass().getSimpleName();
            String elementName = element.getName();

            StringBuilder props = new StringBuilder();
            int count = 0;
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext() && count < 20) {
                org.apache.jmeter.testelement.property.JMeterProperty property = propertyIterator.next();
                String propName = property.getName();
                String propValue = property.getStringValue();
                if (propValue == null || propValue.isEmpty()) continue;
                if (propName.startsWith("TestElement.") || propName.equals("guiclass") || propName.equals("testclass")) continue;
                if (propValue.length() > 200) propValue = propValue.substring(0, 200) + "...";
                props.append("- ").append(propName).append(": ").append(propValue).append("\n");
                count++;
            }

            String prompt = buildOptimizePrompt(elementType, elementName,
                    props.length() > 0 ? props.toString() : "(нет настроенных свойств)\n");

            String recommendations = aiService.generateResponse(java.util.Collections.singletonList(prompt));

            return "# Рекомендации по оптимизации: " + elementName + " (" + elementType + ")\n\n" +
                    recommendations;

        } catch (Exception e) {
            log.error("Error getting optimization recommendations", e);
            return "Ошибка при анализе элемента: " + e.getMessage();
        }
    }

    static String buildPromptForTest(String elementType, String elementName, String props) {
        return buildOptimizePrompt(elementType, elementName, props);
    }

    private static String buildOptimizePrompt(String elementType, String elementName, String props) {
        return "Ты эксперт по Apache JMeter. Отвечай на русском языке.\n" +
                "Проанализируй конфигурацию элемента JMeter и дай конкретные рекомендации.\n\n" +
                "Формат:\n" +
                "1. Summary\n" +
                "2. Risks\n" +
                "3. Recommendations\n\n" +
                "Тип: " + elementType + "\n" +
                "Название: " + elementName + "\n\n" +
                "Свойства:\n" + props;
    }
}

