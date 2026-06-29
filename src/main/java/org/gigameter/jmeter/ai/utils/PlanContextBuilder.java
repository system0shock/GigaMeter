package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the two-layer agent context: a whole-plan skeleton (breadth) plus full detail of the
 * selected subtrees (depth), within a char budget. Degradation is always surfaced, never silent.
 */
public final class PlanContextBuilder {

    private PlanContextBuilder() {
    }

    /** Drops any selected id that is a descendant of another selected id; preserves order. */
    public static List<Integer> topmostSelected(List<ElementEntry> elements, Collection<Integer> selectedIds) {
        if (selectedIds == null) {
            return new ArrayList<>();
        }
        Set<Integer> selected = new HashSet<>(selectedIds);
        List<Integer> out = new ArrayList<>();
        for (ElementEntry e : elements) {
            if (!selected.contains(e.id)) {
                continue;
            }
            int idx = e.id - 1;
            boolean hasSelectedAncestor = false;
            for (int a = 0; a < idx; a++) {
                if (selected.contains(elements.get(a).id)
                        && JMeterPlanSerializer.subtreeEnd(elements, a) > idx) {
                    hasSelectedAncestor = true;
                    break;
                }
            }
            if (!hasSelectedAncestor) {
                out.add(e.id);
            }
        }
        return out;
    }

    /** Order-independent stable hash of the selection (empty selection => "none"). */
    public static String selectionHash(Collection<Integer> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer id : new TreeSet<>(selectedIds)) {
            sb.append(id).append(',');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Builds the two-layer context string: skeleton (whole plan) + detail subtrees for selected ids.
     * Budget is enforced with visible messages; degradation is never silent.
     */
    public static String build(SerializedPlan plan, Collection<Integer> selectedIds,
                               int collapseThreshold, int maxChars) {
        List<ElementEntry> elements = plan.elements;
        List<Integer> topmost = topmostSelected(elements, selectedIds);
        Set<Integer> expanded = new HashSet<>(topmost);

        String skeleton = PlanSkeleton.render(elements, collapseThreshold, expanded);

        StringBuilder sb = new StringBuilder();
        sb.append("СТРУКТУРА ПЛАНА (скелет, #id для операций jmeter-ops):\n");
        sb.append(skeleton);

        if (plan.truncated) {
            sb.append("\n(⚠ план превышает ").append(JMeterPlanSerializer.SKELETON_MAX_ELEMENTS)
              .append(" элементов: показаны первые, остальные не вошли в скелет)\n");
        }

        if (sb.length() > maxChars) {
            sb.append("\n(⚠ план очень большой: контекст не ужат до бюджета, возможны ограничения модели)\n");
            return sb.toString();
        }

        if (topmost.isEmpty()) {
            return sb.toString();
        }

        sb.append("\nДЕТАЛИ ВЫДЕЛЕННЫХ ВЕТОК:\n");
        List<Integer> overflow = new ArrayList<>();
        for (Integer id : topmost) {
            int idx = id - 1;
            int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
            String detail = stripTreeBanner(plan.toReadableTree(idx, end));
            if (sb.length() + detail.length() > maxChars) {
                overflow.add(id);
                continue;
            }
            sb.append(detail);
        }
        for (Integer id : overflow) {
            sb.append("(детали #").append(id).append(" не влезли в бюджет — выделите меньше)\n");
        }
        return sb.toString();
    }

    /** Drops the leading "Структура JMeter…" banner line that toReadableTree prepends, so the
     *  DETAIL section is not polluted with one banner per selected subtree. */
    private static String stripTreeBanner(String tree) {
        int nl = tree.indexOf('\n');
        return nl >= 0 ? tree.substring(nl + 1) : tree;
    }
}
