package org.gigameter.jmeter.ai.service.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of applying a {@link PlanOp} batch: a per-op log, any {@code get_element} dumps to feed
 * back to the agent, and whether anything was actually mutated (so the UI knows to offer undo).
 */
public final class OpsApplyResult {

    private final List<String> applied = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> reads = new ArrayList<>();
    private boolean mutated;
    private boolean staleTree;

    public void recordApplied(String msg) { applied.add(msg); mutated = true; }
    public void recordReadOnly(String msg) { applied.add(msg); }
    public void recordFailure(String msg) { failures.add(msg); }
    public void recordRead(String dump) { reads.add(dump); }
    public void markStaleTree() { staleTree = true; }

    public boolean isMutated() { return mutated; }
    public boolean isStaleTree() { return staleTree; }
    public boolean hasFailures() { return !failures.isEmpty(); }
    public List<String> reads() { return reads; }

    /** Human-readable summary for the chat panel. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (staleTree) {
            sb.append("Дерево изменилось с момента анализа — операции отклонены. "
                    + "Обновите контекст и повторите.\n");
        }
        if (!applied.isEmpty()) {
            sb.append("Выполнено:\n");
            for (String s : applied) sb.append("  ✓ ").append(s).append("\n");
        }
        if (!failures.isEmpty()) {
            sb.append("Ошибки:\n");
            for (String s : failures) sb.append("  ✗ ").append(s).append("\n");
        }
        if (!reads.isEmpty()) {
            sb.append("Данные элементов:\n");
            for (String s : reads) sb.append(s).append("\n");
        }
        if (sb.length() == 0) {
            sb.append("Ничего не применено.");
        }
        return sb.toString().trim();
    }
}
