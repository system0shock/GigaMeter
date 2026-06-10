package org.gigameter.jmeter.ai.service;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.gigameter.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;

/**
 * Handles code refactoring and improvement operations for JSR223 (Groovy) scripts using AI.
 */
public class CodeRefactorer {
    private static final Logger log = LoggerFactory.getLogger(CodeRefactorer.class);

    private static final String REFACTOR_PROMPT =
            "Refactor the following JMeter JSR223 Groovy script following best practices.\n" +
            "IMPORTANT: Return ONLY the refactored code — no explanations, no markdown, no backticks.\n\n" +
            "Apply JMeter/Groovy best practices:\n" +
            "- Use vars.get('NAME') instead of ${NAME} interpolation inside scripts\n" +
            "- Use props.get('prop.name') for JMeter properties\n" +
            "- Use log.info/warn/error for logging (not System.out.println)\n" +
            "- Use prev.getResponseDataAsString() / prev.getResponseCode() to read sampler results\n" +
            "- Wrap risky operations in try-catch with log.error in catch block\n" +
            "- Prefer 'def' for local variables; avoid unnecessary raw types\n" +
            "- Use vars.putObject('NAME', obj) / vars.getObject('NAME') for non-string cross-sampler data\n" +
            "- Use props.put('name', value) for sharing data across threads\n" +
            "- Extract repeated logic into closures or helper methods\n" +
            "- Improve naming for clarity\n\n" +
            "CODE TO REFACTOR:";

    private static final String REFACTOR_TRY_CATCH_FINALLY_PROMPT =
            "Wrap the following JMeter JSR223 Groovy script with proper try-catch-finally error handling.\n" +
            "IMPORTANT: Return ONLY the refactored code — no explanations, no markdown, no backticks.\n\n" +
            "Rules:\n" +
            "- Wrap the main logic in try { } catch (Exception e) { } finally { }\n" +
            "- In catch: log.error('Description of what failed', e)\n" +
            "- In catch: set SampleResult failure if appropriate: prev.setSuccessful(false); prev.setResponseMessage(e.getMessage())\n" +
            "- In finally: release resources (close streams, connections, etc.) if any\n" +
            "- Preserve all existing functionality\n\n" +
            "CODE TO REFACTOR:";

    private static final String OPTIMIZE_PROMPT =
            "Optimize the following JMeter JSR223 Groovy script for performance.\n" +
            "IMPORTANT: Return ONLY the optimized code — no explanations, no markdown, no backticks.\n\n" +
            "Optimization rules:\n" +
            "- Replace ${VAR} with vars.get('VAR') — avoids repeated string parsing overhead\n" +
            "- Replace System.out.println with log.debug to avoid console I/O in load tests\n" +
            "- Do NOT use Thread.sleep() in scripts — use a Constant Timer element instead\n" +
            "- Minimize object allocation inside loops\n" +
            "- Use StringBuilder for string concatenation in loops\n" +
            "- Prefer props.get() for read-heavy shared data cached at JVM level\n" +
            "- Avoid heavy file I/O in scripts — use CSV Data Set Config element instead\n" +
            "- Cache expensive initializations using props or static fields where safe\n" +
            "- Use Groovy's native JSON/XML parsers (JsonSlurper, XmlSlurper) over manual parsing\n\n" +
            "CODE TO OPTIMIZE:";

    private static final String ADD_LOGGING_PROMPT =
            "Add proper JMeter-compatible logging to the following JSR223 Groovy script.\n" +
            "IMPORTANT: Return ONLY the modified code — no explanations, no markdown, no backticks.\n\n" +
            "Logging rules:\n" +
            "- Use log.info() for key informational messages (thread name, extracted values, decisions)\n" +
            "- Use log.debug() for verbose/diagnostic messages\n" +
            "- Use log.warn() for recoverable issues\n" +
            "- Use log.error('message', exception) inside catch blocks\n" +
            "- Include thread context: ctx.getThreadNum(), vars.get('VAR') in messages where relevant\n" +
            "- Do NOT use println() or System.out — they do not appear in JMeter logs\n" +
            "- Add entry/exit logs for complex conditional branches\n" +
            "- Log the values of key variables after extraction or assignment\n\n" +
            "CODE:";

    private static final String FIX_VARIABLES_PROMPT =
            "Convert JMeter variable/property syntax in the following JSR223 Groovy script to use the proper API.\n" +
            "IMPORTANT: Return ONLY the converted code — no explanations, no markdown, no backticks.\n\n" +
            "Conversion rules:\n" +
            "- ${VAR_NAME} in Groovy strings → vars.get('VAR_NAME')\n" +
            "- vars['NAME'] → vars.get('NAME')\n" +
            "- Setting variables: vars.put('NAME', value.toString()) for strings\n" +
            "- Setting objects: vars.putObject('NAME', obj) — use for Lists, Maps, etc.\n" +
            "- Reading objects: vars.getObject('NAME') — cast to the expected type\n" +
            "- JMeter properties: ${__P(prop.name,default)} → props.get('prop.name', 'default')\n" +
            "- Cross-thread sharing: props.put('name', value) / props.get('name')\n" +
            "- Context: ctx.getThreadNum() for thread number, ctx.getThreadGroup().getName() for group name\n\n" +
            "CODE:";

    private final AiService aiService;

    public CodeRefactorer(AiService aiService) {
        this.aiService = aiService;
    }

    public boolean refactorSelectedCode(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, REFACTOR_PROMPT, "Рефакторинг кода");
    }

    public boolean refactorTryCatchFinally(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, REFACTOR_TRY_CATCH_FINALLY_PROMPT, "Рефакторинг Try/Catch/Finally");
    }

    public boolean optimizeForPerformance(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, OPTIMIZE_PROMPT, "Оптимизация кода");
    }

    public boolean addLogging(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, ADD_LOGGING_PROMPT, "Добавление логирования");
    }

    public boolean fixVariables(RSyntaxTextArea textArea) {
        return processRefactoring(textArea, FIX_VARIABLES_PROMPT, "Исправление переменных");
    }

    private boolean processRefactoring(RSyntaxTextArea textArea, String promptTemplate, String messageTitle) {
        String selectedText = textArea.getSelectedText();
        if (selectedText == null || selectedText.isEmpty()) {
            JOptionPane.showMessageDialog(
                    textArea,
                    "Сначала выделите код",
                    messageTitle,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        try {
            String prompt = promptTemplate + "\n\n" + selectedText;
            String model = resolveModel();
            String refactoredCode = aiService.generateResponse(List.of(prompt), model);
            refactoredCode = cleanUpCodeResponse(refactoredCode);
            textArea.replaceSelection(refactoredCode);
            return true;
        } catch (Exception ex) {
            log.error("Error during code refactoring", ex);
            JOptionPane.showMessageDialog(
                    textArea,
                    "Ошибка рефакторинга: " + ex.getMessage(),
                    "Ошибка рефакторинга",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private String resolveModel() {
        if (aiService instanceof CliAiService) {
            return "cli";
        }
        return AiConfig.getProperty("giga.default.model", "GigaChat");
    }

    private String cleanUpCodeResponse(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        String cleaned = response;

        // Remove opening code fence with optional language tag
        cleaned = cleaned.replaceAll("^```\\w*\\s*\\n", "");

        // Remove closing code fence
        cleaned = cleaned.replaceAll("```\\s*$", "");

        // Remove any remaining triple backticks
        cleaned = cleaned.replace("```", "");

        // Remove "Here's the refactored code:" style preambles
        cleaned = cleaned.replaceAll("(?i)^.*?(here'?s\\s+the\\s+refactored\\s+code:?\\s*\\n)", "");

        // Remove trailing note/explanation paragraphs
        cleaned = cleaned.replaceAll("(?i)\\n\\s*\\n.*?(note|explanation|changes|improvements):?.*$", "");

        return cleaned.trim();
    }
}
