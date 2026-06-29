package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlanSkeletonHashTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    @Test
    void identicalSiblingSubtreesShareHash() {
        // #1 Plan > [#2 HM "Auth", #3 HM "Auth"] — identical leaves
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "HeaderManager", "Auth"));
        l.add(e(3, 1, "HeaderManager", "Auth"));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertEquals(h.get(2), h.get(3));
    }

    @Test
    void sameNameDifferentChildrenDifferHash() {
        // Two thread groups both named "Дебаг" but different content
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "Дебаг"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "Дебаг"));
        l.add(e(5, 2, "HTTPSamplerProxy", "GET /b"));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertNotEquals(h.get(2), h.get(4));
    }

    @Test
    void differentPropsDifferHash() {
        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("HTTPSampler.path", "/a");
        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("HTTPSampler.path", "/b");
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(new ElementEntry(2, 1, "HTTPSamplerProxy", "S", p1));
        l.add(new ElementEntry(3, 1, "HTTPSamplerProxy", "S", p2));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertNotEquals(h.get(2), h.get(3));
    }
}
