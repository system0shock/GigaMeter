package org.gigameter.jmeter.ai.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks token usage for Sber GigaChat responses.
 */
public class GigaChatUsage {
    private static final Logger log = LoggerFactory.getLogger(GigaChatUsage.class);

    private static final GigaChatUsage INSTANCE = new GigaChatUsage();

    private final List<UsageRecord> usageHistory = new ArrayList<>();

    private GigaChatUsage() {
    }

    public static GigaChatUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Records usage from raw GigaChat JSON response.
     *
     * @param rawResponse API response body
     * @param model       model id used for request
     */
    public synchronized void recordUsageFromResponse(String rawResponse, String model) {
        if (rawResponse == null || rawResponse.isEmpty()) {
            return;
        }

        long promptTokens = extractLongField(rawResponse, "prompt_tokens", "promptTokens", "input_tokens");
        long completionTokens = extractLongField(rawResponse, "completion_tokens", "completionTokens", "output_tokens");
        long totalTokens = extractLongField(rawResponse, "total_tokens", "totalTokens");

        if (totalTokens <= 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = promptTokens + completionTokens;
        }

        if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0) {
            log.debug("No usage fields found in GigaChat response");
            return;
        }

        String cleanModel = model == null ? "unknown" : model;
        if (cleanModel.startsWith("giga:")) {
            cleanModel = cleanModel.substring(5);
        }

        usageHistory.add(new UsageRecord(new Date(), cleanModel, promptTokens, completionTokens, totalTokens));
        log.info("Recorded GigaChat usage: model={}, prompt={}, completion={}, total={}",
                cleanModel, promptTokens, completionTokens, totalTokens);
    }

    public synchronized String getUsageSummary() {
        if (usageHistory.isEmpty()) {
            return "No GigaChat usage data available yet. Send at least one request first.";
        }

        long totalPrompt = 0;
        long totalCompletion = 0;
        long total = 0;
        for (UsageRecord record : usageHistory) {
            totalPrompt += record.promptTokens;
            totalCompletion += record.completionTokens;
            total += record.totalTokens;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("# Sber GigaChat Usage Summary\n\n");
        summary.append("## Overall Summary\n");
        summary.append("- **Total Conversations**: ").append(usageHistory.size()).append("\n");
        summary.append("- **Total Input Tokens**: ").append(totalPrompt).append("\n");
        summary.append("- **Total Output Tokens**: ").append(totalCompletion).append("\n");
        summary.append("- **Total Tokens**: ").append(total).append("\n\n");

        summary.append("## Recent Conversations\n");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int startIndex = Math.max(0, usageHistory.size() - 10);
        for (int i = startIndex; i < usageHistory.size(); i++) {
            UsageRecord record = usageHistory.get(i);
            summary.append("### Conversation ").append(i + 1 - startIndex).append("\n");
            summary.append("- **Date**: ").append(dateFormat.format(record.timestamp)).append("\n");
            summary.append("- **Model**: ").append(record.model).append("\n");
            summary.append("- **Input Tokens**: ").append(record.promptTokens).append("\n");
            summary.append("- **Output Tokens**: ").append(record.completionTokens).append("\n");
            summary.append("- **Total Tokens**: ").append(record.totalTokens).append("\n\n");
        }

        return summary.toString();
    }

    private long extractLongField(String json, String... fieldNames) {
        for (String field : fieldNames) {
            Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored) {
                    // Continue with next matching field variant.
                }
            }
        }
        return 0L;
    }

    private static class UsageRecord {
        private final Date timestamp;
        private final String model;
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        private UsageRecord(Date timestamp, String model, long promptTokens, long completionTokens, long totalTokens) {
            this.timestamp = timestamp;
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }
}
