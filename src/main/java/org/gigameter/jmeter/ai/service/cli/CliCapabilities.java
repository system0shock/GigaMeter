package org.gigameter.jmeter.ai.service.cli;

/**
 * Per-provider capability descriptor. Lets {@link CliAiService} stay provider-agnostic instead of
 * baking qwen specifics into a base class and overriding only a property prefix (the prototype's
 * abstract-class-per-CLI smell).
 *
 * <p>Values are resolved from configuration so a provider whose flags are not yet confirmed (e.g.
 * GigaCode CLI) can be tuned without code changes; the Stage-0 spike feeds these defaults.
 */
public final class CliCapabilities {

    /** Pass the prompt on the child's stdin (true) or as a {@code -p <prompt>} argument (false). */
    public final boolean promptViaStdin;
    /** Request structured output via {@code --output-format json}. */
    public final boolean jsonOutput;
    /** Flag used to inject the system prompt (e.g. {@code --append-system-prompt}), or null. */
    public final String systemPromptFlag;
    /** Whether {@code --resume <id>} is supported (used for CLI sessions). */
    public final boolean supportsResume;
    /** Whether to pass {@code --yolo} (auto-approve). Off by default for safety. */
    public final boolean yolo;
    /** Whether {@code --output-format stream-json --include-partial-messages} is supported. */
    public final boolean streamJson;
    /**
     * Whether to keep a persistent CLI session ({@code --chat-recording} + {@code --resume}) so the
     * agent remembers prior turns and the plugin can send only the new turn instead of the full
     * history each time. Requires {@link #supportsResume}.
     */
    public final boolean sessions;

    public CliCapabilities(boolean promptViaStdin, boolean jsonOutput, String systemPromptFlag,
                           boolean supportsResume, boolean yolo) {
        this(promptViaStdin, jsonOutput, systemPromptFlag, supportsResume, yolo, false, false);
    }

    public CliCapabilities(boolean promptViaStdin, boolean jsonOutput, String systemPromptFlag,
                           boolean supportsResume, boolean yolo, boolean streamJson) {
        this(promptViaStdin, jsonOutput, systemPromptFlag, supportsResume, yolo, streamJson, false);
    }

    public CliCapabilities(boolean promptViaStdin, boolean jsonOutput, String systemPromptFlag,
                           boolean supportsResume, boolean yolo, boolean streamJson, boolean sessions) {
        this.promptViaStdin = promptViaStdin;
        this.jsonOutput = jsonOutput;
        this.systemPromptFlag = (systemPromptFlag == null || systemPromptFlag.trim().isEmpty())
                ? null : systemPromptFlag.trim();
        this.supportsResume = supportsResume;
        this.yolo = yolo;
        this.streamJson = streamJson;
        this.sessions = sessions && supportsResume;
    }
}
