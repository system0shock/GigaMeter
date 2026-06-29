package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the whole plan as a compact, prop-light skeleton with sibling collapse keyed on a
 * recursive structural fingerprint. Pure functions over the preorder+depth element list, so they
 * are unit-testable without a live JMeter tree.
 */
public final class PlanSkeleton {

    private PlanSkeleton() {
    }

    /** Maps each element id to a hash of its full subtree (type + name + props + children). */
    public static Map<Integer, String> subtreeHashes(List<ElementEntry> elements) {
        Map<Integer, String> byId = new LinkedHashMap<>();
        if (!elements.isEmpty()) {
            hashAt(elements, 0, byId);
        }
        return byId;
    }

    /** Returns the hash for the subtree at {@code idx}, recording it (and descendants) into {@code out}. */
    private static String hashAt(List<ElementEntry> elements, int idx, Map<Integer, String> out) {
        ElementEntry e = elements.get(idx);
        int depth = e.depth;
        StringBuilder sb = new StringBuilder();
        sb.append(e.type).append("").append(e.name).append("").append(e.props);
        int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
        int j = idx + 1;
        while (j < end) {
            if (elements.get(j).depth == depth + 1) {
                sb.append('(').append(hashAt(elements, j, out)).append(')');
            }
            j = JMeterPlanSerializer.subtreeEnd(elements, j);
        }
        String h = Integer.toHexString(sb.toString().hashCode());
        out.put(e.id, h);
        return h;
    }
}
