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

    @Test
    void representativeLineShowsInlineProps() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        LinkedHashMap<String, String> props = new LinkedHashMap<>();
        props.put("HTTPSampler.method", "GET");
        props.put("HTTPSampler.path", "/api");
        l.add(new ElementEntry(2, 1, "HTTPSamplerProxy", "Req", props));
        l.add(new ElementEntry(3, 1, "HTTPSamplerProxy", "Req", props));
        l.add(new ElementEntry(4, 1, "HTTPSamplerProxy", "Req", props));
        String out = PlanSkeleton.render(l, 3, Collections.emptySet());
        // representative line should contain inline summary tokens
        assertTrue(out.contains("GET"), "expected GET in output:\n" + out);
        assertTrue(out.contains("/api"), "expected /api in output:\n" + out);
        // collapsed refs should be present
        assertTrue(out.contains("≡ #"), "expected collapse refs in output:\n" + out);
    }

    @Test
    void expandedSiblingExcludedFromCollapseCount() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        // 3 identical HeaderManager siblings (empty props -> same hash)
        l.add(e(2, 1, "HeaderManager", "Auth"));
        l.add(e(3, 1, "HeaderManager", "Auth"));
        l.add(e(4, 1, "HeaderManager", "Auth"));
        Set<Integer> expanded = new HashSet<>();
        expanded.add(2); // expand the first one
        String out = PlanSkeleton.render(l, 3, expanded);
        // expanded one shows marker
        assertTrue(out.contains("раскрыто ниже"), "expected expanded marker:\n" + out);
        // only 2 non-expanded siblings remain — below threshold 3, so no collapse
        assertFalse(out.contains("≡ #"), "expected no collapse refs:\n" + out);
        // all three ids still appear
        assertTrue(out.contains("#2"), "expected #2 in output:\n" + out);
        assertTrue(out.contains("#3"), "expected #3 in output:\n" + out);
        assertTrue(out.contains("#4"), "expected #4 in output:\n" + out);
    }
}
