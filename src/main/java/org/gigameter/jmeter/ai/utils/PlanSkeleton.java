package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Renders the whole plan prop-light with sibling collapse and expanded-node markers. */
    public static String render(List<ElementEntry> elements, int collapseThreshold, Set<Integer> expandedIds) {
        StringBuilder sb = new StringBuilder();
        if (elements.isEmpty()) {
            return sb.toString();
        }
        Map<Integer, String> hashes = subtreeHashes(elements);
        Set<Integer> expanded = expandedIds == null ? java.util.Collections.emptySet() : expandedIds;
        renderSubtree(elements, 0, hashes, collapseThreshold, expanded, false, sb);
        return sb.toString();
    }

    private static void renderSubtree(List<ElementEntry> elements, int idx, Map<Integer, String> hashes,
                                      int threshold, Set<Integer> expanded, boolean showProps, StringBuilder sb) {
        ElementEntry e = elements.get(idx);
        appendLine(e, showProps ? JMeterPlanSerializer.inlineSummary(e.type, e.props) : "", null, false, sb);

        List<Integer> children = directChildren(elements, idx);
        Map<String, Integer> counts = new HashMap<>();
        for (int ci : children) {
            if (expanded.contains(elements.get(ci).id)) continue;
            String h = hashes.get(elements.get(ci).id);
            counts.merge(h, 1, Integer::sum);
        }
        Map<String, Integer> repId = new HashMap<>();
        for (int ci : children) {
            ElementEntry child = elements.get(ci);
            if (expanded.contains(child.id)) {
                appendLine(child, "", null, true, sb); // expanded marker, skip subtree
                continue;
            }
            String h = hashes.get(child.id);
            if (counts.get(h) >= threshold) {
                Integer rep = repId.get(h);
                if (rep == null) {
                    repId.put(h, child.id);
                    renderSubtree(elements, ci, hashes, threshold, expanded, true, sb); // representative w/ props
                } else {
                    appendLine(child, "", rep, false, sb); // ≡ #rep, skip subtree
                }
            } else {
                renderSubtree(elements, ci, hashes, threshold, expanded, false, sb);
            }
        }
    }

    private static List<Integer> directChildren(List<ElementEntry> elements, int idx) {
        List<Integer> out = new ArrayList<>();
        int depth = elements.get(idx).depth;
        int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
        int j = idx + 1;
        while (j < end) {
            if (elements.get(j).depth == depth + 1) {
                out.add(j);
            }
            j = JMeterPlanSerializer.subtreeEnd(elements, j);
        }
        return out;
    }

    /** Appends one skeleton line. {@code refId} non-null => "≡ #refId"; {@code expandedMarker} => "(раскрыто ниже ↓)". */
    private static void appendLine(ElementEntry e, String inline, Integer refId, boolean expandedMarker, StringBuilder sb) {
        String indent = repeat("  ", Math.max(0, e.depth));
        sb.append('#').append(e.id).append(' ').append(indent)
          .append("└─ [").append(JMeterPlanSerializer.friendlyType(e.type)).append("] \"").append(e.name).append('"');
        if (refId != null) {
            sb.append(" ≡ #").append(refId);
        } else if (expandedMarker) {
            sb.append(" (раскрыто ниже ↓)");
        } else if (inline != null && !inline.isEmpty()) {
            sb.append(" | ").append(inline);
        }
        sb.append('\n');
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }

    /** Returns the hash for the subtree at {@code idx}, recording it (and descendants) into {@code out}. */
    private static String hashAt(List<ElementEntry> elements, int idx, Map<Integer, String> out) {
        ElementEntry e = elements.get(idx);
        int depth = e.depth;
        StringBuilder sb = new StringBuilder();
        sb.append(e.type).append("\u0001").append(e.name).append("\u0001").append(e.props);
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
