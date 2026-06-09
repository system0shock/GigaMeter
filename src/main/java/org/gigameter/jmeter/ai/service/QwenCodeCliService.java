package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.utils.AiConfig;

/**
 * AI service backed by the locally installed Qwen Code CLI (qwen -p "...").
 * Install: npm i -g @qwen-code/qwen-code
 * Configure: qwen.cli.command in user.properties
 */
public class QwenCodeCliService extends CliAiService {

    public QwenCodeCliService() {
        super(
            AiConfig.getProperty("qwen.cli.command", "qwen"),
            Long.parseLong(AiConfig.getProperty("qwen.cli.timeout.ms", "60000")),
            Boolean.parseBoolean(AiConfig.getProperty("qwen.cli.use_json_output", "true")),
            Boolean.parseBoolean(AiConfig.getProperty("qwen.cli.inject_all_files", "false")),
            Boolean.parseBoolean(AiConfig.getProperty("qwen.cli.yolo", "false")),
            AiConfig.getProperty("qwen.cli.extra_args", "")
        );
    }

    @Override
    public String getName() {
        return "Qwen Code CLI";
    }

    @Override
    protected String getConfigPrefix() {
        return "qwen";
    }
}
