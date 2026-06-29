package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
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
    void overBudgetEmitsVisibleNoteNotSilentDrop() {
        // tiny budget: skeleton fits but detail does not
        String out = PlanContextBuilder.build(plan(), Arrays.asList(2), 3, 1);
        assertTrue(out.contains("СТРУКТУРА ПЛАНА"));     // skeleton always present
        assertTrue(out.contains("не влезли в бюджет") || out.contains("очень большой"));
    }
}
