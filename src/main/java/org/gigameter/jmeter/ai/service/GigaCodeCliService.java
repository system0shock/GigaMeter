package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.service.cli.CliAiService;
import org.gigameter.jmeter.ai.service.cli.CliCapabilities;
import org.gigameter.jmeter.ai.utils.AiConfig;

/**
 * AI service backed by a locally installed GigaCode CLI. Flags mirror Qwen Code by default; the
 * actual supported flags are not publicly documented and are confirmed by the Stage-0 spike, so
 * every capability is overridable via {@code gigacode.cli.*} properties.
 */
public class GigaCodeCliService extends CliAiService {

    private static final String PREFIX = "gigacode";
    private static final String DEFAULT_SYSTEM_PROMPT =
            "Вы — экспертный помощник по Apache JMeter в плагине GigaMeter. " +
            "Отвечайте по-русски, кратко и по делу.";

    public GigaCodeCliService() {
        super(
            "GigaCode CLI",
            PREFIX,
            AiConfig.getProperty(PREFIX + ".cli.command", "gigacode"),
            Long.parseLong(AiConfig.getProperty(PREFIX + ".cli.timeout.ms", "180000")),
            Integer.parseInt(AiConfig.getProperty(PREFIX + ".cli.max.history.size", "10")),
            AiConfig.getProperty(PREFIX + ".cli.system.prompt", DEFAULT_SYSTEM_PROMPT),
            AiConfig.getProperty(PREFIX + ".cli.extra.args", ""),
            new CliCapabilities(
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.prompt.via.stdin", "true")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.json.output", "true")),
                AiConfig.getProperty(PREFIX + ".cli.system.prompt.flag", ""),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.supports.resume", "false")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.yolo", "false")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.stream", "false")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.sessions", "false"))
            ),
            QwenCodeCliService.resolveCharset(AiConfig.getProperty(PREFIX + ".cli.charset", "UTF-8"))
        );
    }
}
