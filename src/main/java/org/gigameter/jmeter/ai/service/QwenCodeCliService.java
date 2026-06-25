package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.service.cli.CliAiService;
import org.gigameter.jmeter.ai.service.cli.CliCapabilities;
import org.gigameter.jmeter.ai.utils.AiConfig;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * AI service backed by a locally installed Qwen Code CLI.
 * Install: {@code npm i -g @qwen-code/qwen-code}. Configure {@code qwen.cli.*} in user.properties.
 */
public class QwenCodeCliService extends CliAiService {

    private static final String PREFIX = "qwen";
    private static final String DEFAULT_SYSTEM_PROMPT =
            "Вы — экспертный помощник по Apache JMeter в плагине GigaMeter. " +
            "Отвечайте по-русски, кратко и по делу.";

    public QwenCodeCliService() {
        super(
            "Qwen Code CLI",
            PREFIX,
            AiConfig.getProperty(PREFIX + ".cli.command", "qwen"),
            Long.parseLong(AiConfig.getProperty(PREFIX + ".cli.timeout.ms", "180000")),
            Integer.parseInt(AiConfig.getProperty(PREFIX + ".cli.max.history.size", "10")),
            AiConfig.getProperty(PREFIX + ".cli.system.prompt", DEFAULT_SYSTEM_PROMPT),
            AiConfig.getProperty(PREFIX + ".cli.extra.args", ""),
            new CliCapabilities(
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.prompt.via.stdin", "true")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.json.output", "true")),
                AiConfig.getProperty(PREFIX + ".cli.system.prompt.flag", ""),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.supports.resume", "true")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.yolo", "false")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.stream", "true")),
                Boolean.parseBoolean(AiConfig.getProperty(PREFIX + ".cli.sessions", "true"))
            ),
            resolveCharset(AiConfig.getProperty(PREFIX + ".cli.charset", "UTF-8"))
        );
    }

    static Charset resolveCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
