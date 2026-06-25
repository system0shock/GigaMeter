package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanOpsValidatorTest {

    @Test
    void validSetPropertyPasses() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse(
                "[{\"op\":\"set_property\",\"id\":3,\"key\":\"k\",\"value\":\"v\"}]");
        PlanOpsValidator.validate(ops); // no throw
        assertTrue(PlanOpsValidator.collectProblems(ops).isEmpty());
    }

    @Test
    void setPropertyMissingKeyAndValueReported() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"set_property\",\"id\":3}]");
        List<String> problems = PlanOpsValidator.collectProblems(ops);
        assertEquals(2, problems.size());
        assertTrue(problems.toString().contains("key"));
        assertTrue(problems.toString().contains("value"));
    }

    @Test
    void setPropertyMissingIdReported() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"set_property\",\"key\":\"k\",\"value\":\"v\"}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).toString().contains("id"));
    }

    @Test
    void addElementRequiresParentIdAndType() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"add_element\"}]");
        String problems = PlanOpsValidator.collectProblems(ops).toString();
        assertTrue(problems.contains("parentId"));
        assertTrue(problems.contains("elementType"));
    }

    @Test
    void addElementValidPasses() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse(
                "[{\"op\":\"add_element\",\"parentId\":1,\"elementType\":\"httpsampler\"}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).isEmpty());
    }

    @Test
    void removeAndGetRequireId() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"remove_element\"},{\"op\":\"get_element\"}]");
        assertEquals(2, PlanOpsValidator.collectProblems(ops).size());
    }

    @Test
    void negativeIdRejected() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"remove_element\",\"id\":-1}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).toString().contains("положительным"));
    }

    @Test
    void newTestPlanNeedsNothing() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"new_test_plan\"}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).isEmpty());
    }

    @Test
    void replaceSubtreeNeedsElement() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"replace_subtree\",\"id\":2}]");
        assertTrue(PlanOpsValidator.collectProblems(ops).toString().contains("element"));
    }

    @Test
    void emptyListRejected() {
        List<String> problems = PlanOpsValidator.collectProblems(Collections.emptyList());
        assertFalse(problems.isEmpty());
        assertThrows(OpsException.class, () -> PlanOpsValidator.validate(Collections.emptyList()));
    }
}
