package org.gigameter.jmeter.ai.service.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsPreviewRendererTest {

    @Test
    void rendersMutatingBatchWithConfirmPrompt() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse(
                "[{\"op\":\"set_property\",\"id\":3,\"key\":\"HTTPSampler.path\",\"value\":\"/api\"},"
                + "{\"op\":\"add_element\",\"parentId\":2,\"elementType\":\"httpsampler\",\"name\":\"Login\"}]");
        String preview = OpsPreviewRenderer.render(ops);
        assertTrue(preview.contains("HTTPSampler.path = /api"));
        assertTrue(preview.contains("Добавить элемент [httpsampler]"));
        assertTrue(preview.contains("Login"));
        assertTrue(preview.contains("Применить?"));
    }

    @Test
    void readOnlyBatchOmitsConfirmPrompt() throws Exception {
        List<PlanOp> ops = PlanOpsParser.parse("[{\"op\":\"get_element\",\"id\":5}]");
        String preview = OpsPreviewRenderer.render(ops);
        assertTrue(preview.contains("только чтение"));
        assertFalse(preview.contains("Применить?"));
    }

    @Test
    void emptyBatchMessage() {
        assertTrue(OpsPreviewRenderer.render(null).contains("Нет операций"));
    }
}
