package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FencedOpsExtractorTest {

    @Test
    void extractsBodyOfSingleBlock() {
        String reply = "Готово.\n```jmeter-ops\n[{\"op\":\"remove_element\",\"id\":3}]\n```\nВот.";
        assertEquals("[{\"op\":\"remove_element\",\"id\":3}]", FencedOpsExtractor.extract(reply));
        assertTrue(FencedOpsExtractor.hasOps(reply));
    }

    @Test
    void returnsNullWhenNoBlock() {
        assertNull(FencedOpsExtractor.extract("просто текстовый ответ без операций"));
        assertFalse(FencedOpsExtractor.hasOps("nothing here"));
        assertNull(FencedOpsExtractor.extract(null));
        assertNull(FencedOpsExtractor.extract(""));
    }

    @Test
    void recoversTruncatedUnclosedBlock() {
        // Opening fence, no closing fence (stream truncated) — we now recover the ops payload so
        // JSON-repair downstream can close it, instead of silently dropping the edit.
        String reply = "```jmeter-ops\n[{\"op\":\"remove_element\",\"id\":1}]";
        assertEquals("[{\"op\":\"remove_element\",\"id\":1}]", FencedOpsExtractor.extract(reply));
    }

    @Test
    void recoversTruncatedJsonMissingClose() {
        // Opening fence + truncated JSON (no closing bracket, no closing fence).
        String reply = "Готово:\n```jmeter-ops\n[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"threadgroup\"";
        String got = FencedOpsExtractor.extract(reply);
        assertNotNull(got);
        assertTrue(got.startsWith("[{\"op\":\"add_element\""));
    }

    @Test
    void prefersLastCompleteBlock() {
        String reply = "Черновик:\n```jmeter-ops\n[{\"op\":\"get_element\",\"id\":1}]\n```\n"
                + "Финал:\n```jmeter-ops\n[{\"op\":\"remove_element\",\"id\":2}]\n```";
        assertEquals("[{\"op\":\"remove_element\",\"id\":2}]", FencedOpsExtractor.extract(reply));
    }

    @Test
    void tagIsCaseInsensitiveAndToleratesSpaces() {
        String reply = "``` JMeter-Ops \n[{\"op\":\"new_test_plan\"}]\n```";
        assertEquals("[{\"op\":\"new_test_plan\"}]", FencedOpsExtractor.extract(reply));
    }

    @Test
    void acceptsBareJsonArrayWithoutFence() {
        String reply = "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"threadgroup\"}]";
        assertEquals(reply, FencedOpsExtractor.extract(reply));
        assertTrue(FencedOpsExtractor.hasOps(reply));
    }

    @Test
    void acceptsGenericJsonFence() {
        String reply = "```json\n[{\"op\":\"remove_element\",\"id\":2}]\n```";
        assertEquals("[{\"op\":\"remove_element\",\"id\":2}]", FencedOpsExtractor.extract(reply));
    }

    @Test
    void plainTextWithBracketsNotMistakenForOps() {
        assertNull(FencedOpsExtractor.extract("Список шагов: [1] логин [2] выход"));
    }

    @Test
    void multilineBodyPreserved() {
        String reply = "```jmeter-ops\n[\n  {\"op\":\"set_property\",\"id\":3,\"key\":\"k\",\"value\":\"v\"}\n]\n```";
        String body = FencedOpsExtractor.extract(reply);
        assertTrue(body.contains("set_property"));
        assertTrue(body.startsWith("["));
        assertTrue(body.endsWith("]"));
    }
}
