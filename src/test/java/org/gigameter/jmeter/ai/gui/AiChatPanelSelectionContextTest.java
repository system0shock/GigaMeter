package org.gigameter.jmeter.ai.gui;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatPanelSelectionContextTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    private static SerializedPlan plan() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        return new SerializedPlan(l, new LinkedHashMap<>(), false);
    }

    @Test
    void parseIntOrFallsBackOnNonNumeric() {
        org.junit.jupiter.api.Assertions.assertEquals(3, AiChatPanel.parseIntOr("not-a-number", 3));
        org.junit.jupiter.api.Assertions.assertEquals(24000, AiChatPanel.parseIntOr(null, 24000));
        org.junit.jupiter.api.Assertions.assertEquals(7, AiChatPanel.parseIntOr(" 7 ", 0));
    }

    @Test
    void buildsSkeletonWithoutSelection() {
        String out = AiChatPanel.buildPlanContextForTest(plan(), Collections.emptyList(), 3, 100000);
        assertTrue(out.contains("СТРУКТУРА ПЛАНА"));
    }

    @Test
    void buildsDetailForSelection() {
        String out = AiChatPanel.buildPlanContextForTest(plan(), Arrays.asList(2), 3, 100000);
        assertTrue(out.contains("ДЕТАЛИ ВЫДЕЛЕННЫХ ВЕТОК"));
        assertTrue(out.contains("GET /a"));
    }
}
