package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JMeterPlanSerializerHelpersTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    /** Tree: #1 TestPlan(d0) > [#2 TG(d1) > #3 Sampler(d2)], #4 TG(d1) */
    private static List<ElementEntry> sample() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /"));
        l.add(e(4, 1, "ThreadGroup", "TG B"));
        return l;
    }

    @Test
    void subtreeEndCoversDescendants() {
        List<ElementEntry> l = sample();
        assertEquals(3, JMeterPlanSerializer.subtreeEnd(l, 1));
    }

    @Test
    void subtreeEndOfLeafIsNext() {
        List<ElementEntry> l = sample();
        assertEquals(3, JMeterPlanSerializer.subtreeEnd(l, 2)); // leaf #3 -> end 3
    }

    @Test
    void friendlyTypeMapsKnownClass() {
        assertEquals("Thread Group", JMeterPlanSerializer.friendlyType("ThreadGroup"));
    }

    @Test
    void toReadableTreeRangeRendersOnlySublist() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("HTTPSampler.method", "GET");
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(new ElementEntry(2, 1, "ThreadGroup", "TG A", new LinkedHashMap<>()));
        l.add(new ElementEntry(3, 2, "HTTPSamplerProxy", "GET /", props));
        JMeterPlanSerializer.SerializedPlan plan =
                new JMeterPlanSerializer.SerializedPlan(l, new LinkedHashMap<>(), false);
        String out = plan.toReadableTree(1, 3); // TG A + sampler only
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("#2"));
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("#3"));
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("#1"));
    }
}
