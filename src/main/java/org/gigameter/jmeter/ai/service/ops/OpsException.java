package org.gigameter.jmeter.ai.service.ops;

import java.util.Collections;
import java.util.List;

/**
 * Raised when a {@code jmeter-ops} block cannot be parsed or fails validation. Carries a
 * user-facing message (Russian, matching the rest of the chat UI) and, for validation failures, the
 * list of individual problems so the agent can be asked to self-correct.
 */
public class OpsException extends Exception {

    private final List<String> problems;

    public OpsException(String message) {
        this(message, Collections.emptyList());
    }

    public OpsException(String message, List<String> problems) {
        super(message);
        this.problems = problems == null ? Collections.emptyList()
                : Collections.unmodifiableList(problems);
    }

    public OpsException(String message, Throwable cause) {
        super(message, cause);
        this.problems = Collections.emptyList();
    }

    /** Individual validation problems, empty for parse/extraction failures. */
    public List<String> problems() {
        return problems;
    }
}
