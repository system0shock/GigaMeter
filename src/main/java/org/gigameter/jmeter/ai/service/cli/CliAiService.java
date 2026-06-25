package org.gigameter.jmeter.ai.service.cli;

import org.gigameter.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI provider that delegates to a locally installed CLI agent (e.g. Qwen Code, GigaCode).
 *
 * <p>Subprocess handling is delegated to {@link CliTransport} (Windows-safe launch, effective
 * timeout, no stdout/stderr deadlock) and response decoding to {@link CliResponseParser} (real
 * JSON parsing, correct Unicode). Provider differences are described by {@link CliCapabilities}
 * rather than subclassing, so the only thing a concrete provider supplies is configuration and a
 * display name.
 */
public class CliAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(CliAiService.class);

    private final String displayName;
    private final String configPrefix;
    private final String cliCommand;
    private final long timeoutMs;
    private final int maxHistorySize;
    private final String systemPrompt;
    private final List<String> extraArgs;
    private final CliCapabilities caps;
    private final CliTransport transport;

    /** Holds the live process so a UI Stop action can cancel an in-flight request. */
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    /** Session id reported by the CLI on the last turn (for {@code --resume}); null until first reply. */
    private volatile String lastSessionId;

    public CliAiService(String displayName, String configPrefix, String cliCommand, long timeoutMs,
                        int maxHistorySize, String systemPrompt, String extraArgs,
                        CliCapabilities caps, Charset charset) {
        this.displayName = displayName;
        this.configPrefix = configPrefix;
        this.cliCommand = cliCommand;
        this.timeoutMs = timeoutMs;
        this.maxHistorySize = maxHistorySize;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.extraArgs = ArgsParser.parse(extraArgs);
        this.caps = caps;
        this.transport = new CliTransport(charset == null ? StandardCharsets.UTF_8 : charset,
                resolveSandboxDir(configPrefix));
    }

    /**
     * An empty per-provider scratch directory to launch the agent in. Keeps an agentic CLI from
     * treating the JMeter install (or whatever the JVM's cwd is) as a codebase to explore.
     */
    private static java.io.File resolveSandboxDir(String prefix) {
        try {
            java.io.File dir = new java.io.File(
                    System.getProperty("java.io.tmpdir", "."), "gigameter-cli-" + prefix);
            if (dir.isDirectory() || dir.mkdirs()) {
                return dir;
            }
        } catch (RuntimeException e) {
            log.debug("Could not create CLI sandbox dir", e);
        }
        return null;
    }

    @Override
    public String getName() {
        return displayName;
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, null);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (conversation == null || conversation.isEmpty()) {
            return "Error: пустой запрос";
        }
        String prompt = buildPrompt(conversation);

        List<String> command = new ArrayList<>();
        command.add(cliCommand);
        addModelFlag(command, model);
        if (caps.jsonOutput) {
            command.add("--output-format");
            command.add("json");
        }
        if (caps.systemPromptFlag != null && !systemPrompt.isEmpty()) {
            command.add(caps.systemPromptFlag);
            command.add(systemPrompt);
        }
        if (caps.yolo) {
            command.add("--yolo");
        }
        addSessionFlags(command);
        command.addAll(extraArgs);

        String stdin;
        if (caps.promptViaStdin) {
            stdin = prompt;
        } else {
            command.add("-p");
            command.add(prompt);
            stdin = null;
        }

        try {
            CliTransport.Result r = transport.run(command, stdin, timeoutMs, activeProcess::set);
            if (r.timedOut) {
                return "Error: CLI не ответил за " + timeoutMs + " мс. " +
                        "Увеличьте " + configPrefix + ".cli.timeout.ms или проверьте, что "
                        + displayName + " не ждёт интерактивного ввода (например, логина).";
            }
            if (r.exitCode != 0) {
                String err = r.stderr == null ? "" : r.stderr.trim();
                return "Error: " + displayName + " завершился с кодом " + r.exitCode
                        + (err.isEmpty() ? "" : ": " + err);
            }
            if (caps.sessions) {
                captureSessionId(r.stdout);
            }
            String text = CliResponseParser.parse(r.stdout, caps.jsonOutput);
            return text.isEmpty() ? "Error: CLI вернул пустой ответ" : text;
        } catch (java.io.IOException e) {
            log.error("Failed to start CLI '{}'", cliCommand, e);
            return "Error: не удалось запустить \"" + cliCommand + "\". Проверьте "
                    + configPrefix + ".cli.command и что " + displayName + " установлен и доступен в PATH.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: запрос к CLI прерван";
        } finally {
            activeProcess.set(null);
        }
    }

    /** Whether this provider can stream partial output (drives incremental UI rendering). */
    public boolean supportsStreaming() {
        return caps.streamJson;
    }

    /** Whether persistent CLI sessions are enabled for this provider. */
    public boolean sessionsEnabled() {
        return caps.sessions;
    }

    /**
     * Whether a session is already established (so the caller may send only the new turn instead of
     * the full history). False on the first turn and when sessions are disabled.
     */
    public boolean isSessionActive() {
        return caps.sessions && lastSessionId != null;
    }

    /** Clears the session so the next call starts fresh (e.g. on "new chat"). */
    public void resetSession() {
        lastSessionId = null;
    }

    /**
     * Adds {@code --model <id>} when a concrete model is selected. The sentinel {@code "default"}
     * (and null/blank) means "use the CLI's own configured model" — no override.
     */
    private void addModelFlag(List<String> command, String model) {
        if (model != null && !model.trim().isEmpty() && !"default".equalsIgnoreCase(model.trim())) {
            command.add("--model");
            command.add(model.trim());
        }
    }

    /** Adds {@code --chat-recording}/{@code --resume} flags when sessions are enabled. */
    private void addSessionFlags(List<String> command) {
        if (!caps.sessions) {
            return;
        }
        command.add("--chat-recording");
        if (lastSessionId != null) {
            command.add("--resume");
            command.add(lastSessionId);
        }
    }

    /** Extracts the {@code session_id} from a non-streaming JSON reply so a session can be resumed. */
    private void captureSessionId(String stdout) {
        if (stdout == null || stdout.trim().isEmpty()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(stdout.trim());
            String sid = findSessionId(root);
            if (sid != null) {
                lastSessionId = sid;
            }
        } catch (Exception e) {
            log.debug("Could not capture session id from CLI output", e);
        }
    }

    private static String findSessionId(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.JsonNode sid = node.get("session_id");
            if (sid != null && sid.isTextual()) {
                return sid.asText();
            }
        }
        if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode el : node) {
                String s = findSessionId(el);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    /** The CLI session id from the last reply, or {@code null} if none seen yet. */
    public String getLastSessionId() {
        return lastSessionId;
    }

    /**
     * Streaming counterpart of {@link #generateResponse(List)}: runs the CLI with
     * {@code --output-format stream-json --include-partial-messages} and feeds each assistant text
     * delta to {@code onDelta} as it arrives. Returns the full final reply (preferring the canonical
     * {@code result} event, falling back to the concatenated deltas). Falls back to a clear error
     * string on timeout / non-zero exit, exactly like the blocking variant.
     */
    public String generateResponseStreaming(List<String> conversation, java.util.function.Consumer<String> onDelta) {
        return generateResponseStreaming(conversation, null, onDelta);
    }

    public String generateResponseStreaming(List<String> conversation, String model,
                                            java.util.function.Consumer<String> onDelta) {
        if (conversation == null || conversation.isEmpty()) {
            return "Error: пустой запрос";
        }
        String prompt = buildPrompt(conversation);

        List<String> command = new ArrayList<>();
        command.add(cliCommand);
        addModelFlag(command, model);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--include-partial-messages");
        if (caps.systemPromptFlag != null && !systemPrompt.isEmpty()) {
            command.add(caps.systemPromptFlag);
            command.add(systemPrompt);
        }
        if (caps.yolo) {
            command.add("--yolo");
        }
        addSessionFlags(command);
        command.addAll(extraArgs);

        String stdin;
        if (caps.promptViaStdin) {
            stdin = prompt;
        } else {
            command.add("-p");
            command.add(prompt);
            stdin = null;
        }

        StringBuilder deltas = new StringBuilder();
        AtomicReference<String> finalResult = new AtomicReference<>(null);
        java.util.function.Consumer<String> onLine = line -> {
            com.fasterxml.jackson.databind.JsonNode node = StreamJsonParser.parseLine(line);
            if (node == null) {
                return;
            }
            String sid = StreamJsonParser.sessionId(node);
            if (sid != null) {
                lastSessionId = sid;
            }
            String delta = StreamJsonParser.textDelta(node);
            if (delta != null) {
                deltas.append(delta);
                if (onDelta != null) {
                    onDelta.accept(delta);
                }
            }
            String fin = StreamJsonParser.finalResult(node);
            if (fin != null) {
                finalResult.set(fin);
            }
        };

        try {
            CliTransport.Result r = transport.run(command, stdin, timeoutMs, activeProcess::set, onLine);
            if (r.timedOut) {
                return "Error: CLI не ответил за " + timeoutMs + " мс. Увеличьте "
                        + configPrefix + ".cli.timeout.ms или проверьте, что " + displayName
                        + " не ждёт интерактивного ввода.";
            }
            if (r.exitCode != 0) {
                String err = r.stderr == null ? "" : r.stderr.trim();
                return "Error: " + displayName + " завершился с кодом " + r.exitCode
                        + (err.isEmpty() ? "" : ": " + err);
            }
            String fin = finalResult.get();
            String delta = deltas.toString();
            log.info("CLI stream done: resultLen={}, deltaLen={}, stdoutLen={}",
                    fin == null ? -1 : fin.length(), delta.length(),
                    r.stdout == null ? 0 : r.stdout.length());
            // Prefer the streamed deltas: qwen sometimes TRUNCATES the trailing `result` event
            // (observed with --chat-recording), while the concatenated text_deltas are complete.
            // Use whichever is longer (more complete); fall back across them.
            String best = null;
            if (!delta.trim().isEmpty() && (fin == null || delta.length() >= fin.length())) {
                best = delta;
            } else if (fin != null && !fin.trim().isEmpty()) {
                best = fin;
            } else if (!delta.trim().isEmpty()) {
                best = delta;
            }
            if (best != null) {
                return best.trim();
            }
            log.warn("CLI empty reply. stdout tail: {}", tail(r.stdout, 600));
            return "Error: CLI вернул пустой ответ";
        } catch (java.io.IOException e) {
            log.error("Failed to start CLI '{}'", cliCommand, e);
            return "Error: не удалось запустить \"" + cliCommand + "\". Проверьте "
                    + configPrefix + ".cli.command и что " + displayName + " установлен и доступен в PATH.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: запрос к CLI прерван";
        } finally {
            activeProcess.set(null);
        }
    }

    private static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /** Cancels the in-flight request, if any (for a future Stop button). */
    public void cancel() {
        Process p = activeProcess.getAndSet(null);
        if (p != null) {
            try {
                p.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Throwable ignored) {
                // best effort
            }
            p.destroyForcibly();
        }
    }

    private String buildPrompt(List<String> conversation) {
        List<String> history = conversation;
        if (history.size() > maxHistorySize) {
            history = history.subList(history.size() - maxHistorySize, history.size());
        }
        StringBuilder sb = new StringBuilder();
        if (!systemPrompt.isEmpty() && caps.systemPromptFlag == null) {
            // No dedicated system-prompt flag: fold it into the prompt text.
            sb.append(systemPrompt).append("\n\n");
        }
        for (int i = 0; i < history.size(); i++) {
            String msg = history.get(i);
            if (msg == null || msg.trim().isEmpty()) {
                continue;
            }
            sb.append(i % 2 == 0 ? "User: " : "Assistant: ").append(msg).append("\n");
        }
        return sb.toString().trim();
    }
}
