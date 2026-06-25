package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanOpsParserTest {

    @Test
    void parsesArrayOfOps() throws Exception {
        String json = "[{\"op\":\"set_property\",\"id\":3,\"key\":\"HTTPSampler.path\",\"value\":\"/api\"},"
                + "{\"op\":\"remove_element\",\"id\":5}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        assertEquals(2, ops.size());
        assertEquals(OpType.SET_PROPERTY, ops.get(0).op());
        assertEquals(3, ops.get(0).id());
        assertEquals("HTTPSampler.path", ops.get(0).key());
        assertEquals("/api", ops.get(0).value());
        assertEquals(OpType.REMOVE_ELEMENT, ops.get(1).op());
    }

    @Test
    void coercesNonStringValueToString() throws Exception {
        String json = "[{\"op\":\"set_property\",\"id\":1,\"key\":\"ThreadGroup.num_threads\",\"value\":50}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        assertEquals("50", ops.get(0).value());
    }

    @Test
    void parsesAddElementWithProps() throws Exception {
        String json = "[{\"op\":\"add_element\",\"parentId\":2,\"elementType\":\"httpsampler\","
                + "\"name\":\"Login\",\"props\":{\"HTTPSampler.method\":\"POST\",\"HTTPSampler.path\":\"/login\"}}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        PlanOp op = ops.get(0);
        assertEquals(OpType.ADD_ELEMENT, op.op());
        assertEquals(2, op.parentId());
        assertEquals("httpsampler", op.elementType());
        assertEquals("POST", op.props().get("HTTPSampler.method"));
    }

    @Test
    void acceptsSingleObjectForm() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("{\"op\":\"get_element\",\"id\":7}");
        assertEquals(1, ops.size());
        assertEquals(OpType.GET_ELEMENT, ops.get(0).op());
        assertEquals(7, ops.get(0).id());
    }

    @Test
    void acceptsOpsWrapper() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("{\"ops\":[{\"op\":\"new_test_plan\"}]}");
        assertEquals(1, ops.size());
        assertEquals(OpType.NEW_TEST_PLAN, ops.get(0).op());
    }

    @Test
    void replaceSubtreeKeepsElementNode() throws Exception {
        String json = "[{\"op\":\"replace_subtree\",\"id\":4,\"element\":{\"type\":\"httpsampler\",\"name\":\"X\"}}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        assertNotNull(ops.get(0).element());
        assertEquals("X", ops.get(0).element().get("name").asText());
    }

    @Test
    void acceptsPropsAsArrayOfSingleEntryObjects() throws Exception {
        // Real-world qwen output variant observed in Stage-0 smoke.
        String json = "[{\"op\":\"add_element\",\"parentId\":3,\"elementType\":\"headermanager\","
                + "\"name\":\"Headers\",\"props\":[{\"HeaderManager.header.content\":\"Content-Type: application/json\"}]}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        assertEquals("Content-Type: application/json",
                ops.get(0).props().get("HeaderManager.header.content"));
    }

    @Test
    void acceptsPropsAsArrayOfKeyValuePairs() throws Exception {
        String json = "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"httpsampler\","
                + "\"props\":[{\"key\":\"HTTPSampler.method\",\"value\":\"GET\"},"
                + "{\"key\":\"HTTPSampler.path\",\"value\":\"/\"}]}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        assertEquals("GET", ops.get(0).props().get("HTTPSampler.method"));
        assertEquals("/", ops.get(0).props().get("HTTPSampler.path"));
    }

    @Test
    void parsesNestedChildren() throws Exception {
        String json = "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"threadgroup\",\"children\":["
                + "{\"elementType\":\"httpsampler\",\"name\":\"POST /login\",\"children\":["
                + "{\"elementType\":\"responseassertion\",\"props\":{\"assert.code\":\"200\"}}]}]}]";
        List<PlanOp> ops = PlanOpsParser.parse(json);
        PlanOp tg = ops.get(0);
        assertEquals(1, tg.children().size());
        PlanOp sampler = tg.children().get(0);
        assertEquals("httpsampler", sampler.elementType());
        assertEquals(OpType.ADD_ELEMENT, sampler.op()); // implicit op for children
        assertEquals(1, sampler.children().size());
        assertEquals("responseassertion", sampler.children().get(0).elementType());
    }

    @Test
    void nestedChildrenValidate() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse(
                "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"threadgroup\",\"children\":["
                + "{\"elementType\":\"httpsampler\"}]}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).isEmpty());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(OpsException.class, () -> PlanOpsParser.parse("[{not json"));
    }

    @Test
    void rejectsUnknownOp() {
        OpsException ex = assertThrows(OpsException.class,
                () -> PlanOpsParser.parse("[{\"op\":\"frobnicate\",\"id\":1}]"));
        assertTrue(ex.problems().toString().contains("frobnicate"));
    }

    @Test
    void rejectsUnknownField() {
        OpsException ex = assertThrows(OpsException.class,
                () -> PlanOpsParser.parse("[{\"op\":\"remove_element\",\"id\":1,\"bogus\":true}]"));
        assertTrue(ex.problems().toString().contains("bogus"));
    }

    @Test
    void rejectsNonArrayNonObject() {
        assertThrows(OpsException.class, () -> PlanOpsParser.parse("\"just a string\""));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(OpsException.class, () -> PlanOpsParser.parse("   "));
    }

    @Test
    void emptyArrayReturnsEmptyListNotError() throws Exception {
        // Models sometimes append an empty block for informational answers; treat as "no ops".
        assertTrue(PlanOpsParser.parse("[]").isEmpty());
        assertTrue(PlanOpsParser.parse("[ ]").isEmpty());
    }
}
