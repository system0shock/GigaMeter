package org.gigameter.jmeter.ai.service.ops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the operations payload out of an agent reply. Operations are expected inside a fenced block:
 *
 * <pre>
 * ```jmeter-ops
 * [ {"op": "set_property", ...}, ... ]
 * ```
 * </pre>
 *
 * <p>Only the <b>last complete</b> fenced block is honoured: agents often "think out loud" with
 * earlier draft blocks, and the final block is the authoritative one. A reply with no closed block
 * yields {@code null} (treated as a plain chat answer, not an edit).
 */
public final class FencedOpsExtractor {

    /** Language tag that marks an operations block. */
    public static final String FENCE_TAG = "jmeter-ops";

    // Opening fence: ``` optionally followed by the tag, then newline. Body is non-greedy up to the
    // closing ```. DOTALL so the body may span lines. Case-insensitive tag.
    private static final Pattern BLOCK = Pattern.compile(
            "```[ \\t]*" + Pattern.quote(FENCE_TAG) + "[ \\t]*\\r?\\n(.*?)\\r?\\n?```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // An opening triple-backtick fence with an optional language tag.
    private static final Pattern OPEN_FENCE = Pattern.compile("```[a-zA-Z0-9_-]*[ \\t]*\\r?\\n");

    private FencedOpsExtractor() {
    }

    /**
     * @param reply the raw agent text
     * @return the JSON payload from the last {@code jmeter-ops} block (trimmed), or {@code null} if
     *         the reply contains no complete operations block
     */
    public static String extract(String reply) {
        if (reply == null || reply.isEmpty()) {
            return null;
        }
        Matcher m = BLOCK.matcher(reply);
        String last = null;
        while (m.find()) {
            String body = m.group(1);
            if (body != null && !body.trim().isEmpty()) {
                last = body.trim();
            }
        }
        if (last != null) {
            return last;
        }
        // Tolerance: models sometimes omit the ```jmeter-ops fence (bare JSON or ```json block) or
        // get truncated mid-block (opening fence, no close). Strip any leading/trailing fences and,
        // if what remains starts like ops, return it — the JSON repair + strict parser handle the rest.
        String candidate = unwrapFences(reply.trim());
        if (startsLikeOps(candidate)) {
            return candidate;
        }
        return null;
    }

    /** Removes a leading {@code ```[tag]} opening fence (anywhere) and any trailing {@code ```}. */
    private static String unwrapFences(String text) {
        String out = text;
        Matcher open = OPEN_FENCE.matcher(out);
        int from = 0;
        while (open.find()) {
            from = open.end(); // take content after the LAST opening fence
        }
        if (from > 0) {
            out = out.substring(from);
        }
        int close = out.lastIndexOf("```");
        if (close >= 0) {
            out = out.substring(0, close);
        }
        return out.trim();
    }

    /** True if the text begins like an ops array/object (possibly truncated — no closing needed). */
    private static boolean startsLikeOps(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.startsWith("[")) {
            return s.contains("\"op\"") || s.contains("\"elementType\"");
        }
        if (s.startsWith("{")) {
            return s.contains("\"op\"") || s.contains("\"ops\"") || s.contains("\"elementType\"");
        }
        return false;
    }

    /** Whether the reply contains at least one complete operations block. */
    public static boolean hasOps(String reply) {
        return extract(reply) != null;
    }
}
