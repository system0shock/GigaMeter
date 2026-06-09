package org.gigameter.jmeter.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract base for AI providers that delegate to a locally installed CLI tool.
 * Prompts are passed via -p flag (headless mode); history is injected via @file reference.
 */
public abstract class CliAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(CliAiService.class);

    private static final Pattern CONTENT_PATTERN =
            Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    protected final String cliCommand;
    protected final long timeoutMs;
    protected final boolean useJsonOutput;
    protected final boolean injectAllFiles;
    protected final boolean yolo;
    protected final List<String> extraArgs;

    protected CliAiService(String cliCommand, long timeoutMs,
                           boolean useJsonOutput, boolean injectAllFiles,
                           boolean yolo, String extraArgs) {
        this.cliCommand = cliCommand;
        this.timeoutMs = timeoutMs;
        this.useJsonOutput = useJsonOutput;
        this.injectAllFiles = injectAllFiles;
        this.yolo = yolo;
        this.extraArgs = parseExtraArgs(extraArgs);
    }

    @Override
    public String generateResponse(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return "Error: empty conversation";
        }

        String lastUserMsg = conversation.get(conversation.size() - 1);
        String fullPrompt;

        if (conversation.size() > 1) {
            File ctxFile = writeContextFile(conversation.subList(0, conversation.size() - 1));
            if (ctxFile != null) {
                fullPrompt = "@" + ctxFile.getAbsolutePath() + " " + lastUserMsg;
            } else {
                fullPrompt = lastUserMsg;
            }
        } else {
            fullPrompt = lastUserMsg;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(cliCommand);
        cmd.add("-p");
        cmd.add(fullPrompt);
        if (useJsonOutput) {
            cmd.add("--output-format");
            cmd.add("json");
        }
        if (injectAllFiles) {
            cmd.add("--all_files");
        }
        if (yolo) {
            cmd.add("--yolo");
        }
        cmd.addAll(extraArgs);

        log.info("Running CLI: {} -p \"{}...\"", cliCommand,
                fullPrompt.substring(0, Math.min(80, fullPrompt.length())));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();
            // Close stdin — we pass everything via -p arg
            process.getOutputStream().close();

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("CLI timed out after {}ms: {}", timeoutMs, cliCommand);
                return "Error: CLI timeout after " + timeoutMs + "ms. " +
                       "Увеличьте " + getConfigPrefix() + ".cli.timeout.ms в user.properties.";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("CLI exited with code {}: stderr={}", exitCode, stderr);
                return "Error: CLI завершился с кодом " + exitCode +
                       (stderr.isEmpty() ? "" : ": " + stderr.trim());
            }

            return parseResponse(stdout);

        } catch (IOException e) {
            log.error("Failed to start CLI process: {}", cliCommand, e);
            return "Error: CLI не найден: \"" + cliCommand + "\". " +
                   "Проверьте " + getConfigPrefix() + ".cli.command в user.properties. " +
                   "Убедитесь, что " + getName() + " установлен и доступен в PATH.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: CLI прерван";
        }
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        // CLI tools manage their own model selection via ~/.qwen/settings.json
        return generateResponse(conversation);
    }

    /**
     * Returns the config property prefix used in error messages (e.g. "qwen", "gigacode").
     */
    protected abstract String getConfigPrefix();

    /**
     * Writes conversation history to a temp file for @file context injection.
     * Even indices are user messages, odd indices are assistant messages.
     */
    private File writeContextFile(List<String> history) {
        try {
            File tempFile = File.createTempFile("gigameter-context-", ".txt");
            tempFile.deleteOnExit();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < history.size(); i++) {
                sb.append(i % 2 == 0 ? "User: " : "Assistant: ");
                sb.append(history.get(i));
                sb.append("\n\n");
            }
            Files.writeString(tempFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
            return tempFile;
        } catch (IOException e) {
            log.warn("Could not write context temp file, sending without history", e);
            return null;
        }
    }

    /**
     * Extracts the assistant response from CLI output.
     * Tries to parse --output-format json output first, falls back to raw stdout.
     */
    private String parseResponse(String output) {
        if (output == null || output.isBlank()) {
            return "Error: CLI вернул пустой ответ";
        }

        // Try to extract last "content":"..." from JSON array output
        Matcher m = CONTENT_PATTERN.matcher(output);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last != null) {
            return last
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }

        // Fallback: return raw stdout
        return output.trim();
    }

    private static List<String> parseExtraArgs(String extraArgs) {
        if (extraArgs == null || extraArgs.isBlank()) {
            return List.of();
        }
        return Arrays.asList(extraArgs.trim().split("\\s+"));
    }
}
