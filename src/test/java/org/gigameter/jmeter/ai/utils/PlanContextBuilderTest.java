package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanContextBuilderTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    /** #1 Plan > [#2 TG A > #3 sampler], #4 TG B */
    private static SerializedPlan plan() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "TG B"));
        return new SerializedPlan(l, new LinkedHashMap<>(), false);
    }

    @Test
    void buildHasSkeletonAndDetailSections() {
        String out = PlanContextBuilder.build(plan(), Arrays.asList(2), 3, 100000);
        assertTrue(out.contains("СТРУКТУРА ПЛАНА"));
        assertTrue(out.contains("ДЕТАЛИ ВЫДЕЛЕННЫХ ВЕТОК"));
        assertTrue(out.contains("#2"));
        assertTrue(out.contains("GET /a")); // detail of selected TG A includes its sampler
    }

    @Test
    void topmostSelectedDropsDescendantOfSelectedAncestor() {
        // select both TG A (#2) and its sampler (#3) -> only #2 remains
        List<Integer> top = PlanContextBuilder.topmostSelected(plan().elements, Arrays.asList(2, 3));
        assertEquals(Arrays.asList(2), top);
    }

    @Test
    void selectionHashIsOrderIndependent() {
        assertEquals(
                PlanContextBuilder.selectionHash(Arrays.asList(2, 4)),
                PlanContextBuilder.selectionHash(Arrays.asList(4, 2)));
    }

    @Test
    void skeletonOverBudgetEmitsBigPlanWarning() {
        // budget of 1 char: the skeleton itself exceeds the budget
        String out = PlanContextBuilder.build(plan(), Collections.emptyList(), 3, 1);
        assertTrue(out.contains("СТРУКТУРА ПЛАНА"));
        assertTrue(out.contains("очень большой"));
        assertFalse(out.contains("ДЕТАЛИ ВЫДЕЛЕННЫХ ВЕТОК"));
    }

    @Test
    void detailOverBudgetEmitsVisibleNoteWithSkeletonIntact() {
        // #3 has a 400-char name; #2 is selected so #3 only appears in the DETAIL layer
        // The skeleton omits #3's name (expanded marker), so skeleton fits in 250 chars
        // but the detail of #2 (which includes the 400-char name) overflows
        String bigName = repeat("X", 400);
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", bigName));
        SerializedPlan plan = new SerializedPlan(l, new LinkedHashMap<>(), false);

        String out = PlanContextBuilder.build(plan, Arrays.asList(2), 3, 250);
        assertTrue(out.contains("СТРУКТУРА ПЛАНА"));
        assertTrue(out.contains("не влезли в бюджет"));
        assertFalse(out.contains(bigName));
    }

    @Test
    void topmostSelectedHandlesNull() {
        assertTrue(PlanContextBuilder.topmostSelected(plan().elements, null).isEmpty());
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
