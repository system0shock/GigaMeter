package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanSkeletonRenderTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    private static List<ElementEntry> withIdenticalSiblings(int count) {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        for (int i = 0; i < count; i++) {
            l.add(e(2 + i, 1, "HeaderManager", "Auth"));
        }
        return l;
    }

    @Test
    void collapsesThreeOrMoreIdenticalSiblings() {
        String out = PlanSkeleton.render(withIdenticalSiblings(3), 3, Collections.emptySet());
        assertTrue(out.contains("#2"));            // representative
        assertTrue(out.contains("≡ #2"));          // collapsed refs point to #2
        assertTrue(out.contains("#3"));            // ids preserved
        assertTrue(out.contains("#4"));
    }

    @Test
    void doesNotCollapseBelowThreshold() {
        String out = PlanSkeleton.render(withIdenticalSiblings(2), 3, Collections.emptySet());
        assertFalse(out.contains("≡ #"));
    }

    @Test
    void doesNotCollapseSameNameDifferentContent() {
        // Two "Дебаг" thread groups with different children -> different hashes -> no collapse
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "Дебаг"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "Дебаг"));
        l.add(e(5, 2, "HTTPSamplerProxy", "GET /b"));
        l.add(e(6, 1, "ThreadGroup", "Дебаг"));
        l.add(e(7, 2, "HTTPSamplerProxy", "GET /c"));
        String out = PlanSkeleton.render(l, 3, Collections.emptySet());
        assertFalse(out.contains("≡ #"));
        assertTrue(out.contains("GET /a"));
        assertTrue(out.contains("GET /b"));
        assertTrue(out.contains("GET /c"));
    }

    @Test
    void expandedNodeShowsMarkerAndSkipsSubtree() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG"));
        l.add(e(3, 2, "HTTPSamplerProxy", "secret-detail"));
        Set<Integer> expanded = new HashSet<>();
        expanded.add(2);
        String out = PlanSkeleton.render(l, 3, expanded);
        assertTrue(out.contains("раскрыто ниже"));
        assertFalse(out.contains("secret-detail")); // subtree skipped in skeleton
    }
}
