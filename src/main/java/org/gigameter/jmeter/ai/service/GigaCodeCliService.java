package org.gigameter.jmeter.ai.service;

import org.gigameter.jmeter.ai.utils.AiConfig;

/**
 * AI service backed by the locally installed GigaCode CLI (gigacode -p "...").
 * Uses identical flags to Qwen Code CLI with "gigacode" command.
 * Configure: gigacode.cli.command in user.properties
 */
public class GigaCodeCliService extends CliAiService {

    public GigaCodeCliService() {
        super(
            AiConfig.getProperty("gigacode.cli.command", "gigacode"),
            Long.parseLong(AiConfig.getProperty("gigacode.cli.timeout.ms", "60000")),
            Boolean.parseBoolean(AiConfig.getProperty("gigacode.cli.use_json_output", "true")),
            Boolean.parseBoolean(AiConfig.getProperty("gigacode.cli.inject_all_files", "false")),
            Boolean.parseBoolean(AiConfig.getProperty("gigacode.cli.yolo", "false")),
            AiConfig.getProperty("gigacode.cli.extra_args", "")
        );
    }

    @Override
    public String getName() {
        return "GigaCode CLI";
    }

    @Override
    protected String getConfigPrefix() {
        return "gigacode";
    }
}
