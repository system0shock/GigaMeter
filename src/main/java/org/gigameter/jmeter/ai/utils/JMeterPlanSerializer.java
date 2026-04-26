package org.gigameter.jmeter.ai.utils;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a JMeter tree into a compact JSON representation.
 * Uses iterative traversal to avoid stack overflow on deep plans.
 */
public class JMeterPlanSerializer {
    private static final Logger log = LoggerFactory.getLogger(JMeterPlanSerializer.class);

    private static final int DEFAULT_MAX_ELEMENTS = 300;
    private static final int DEFAULT_MAX_DEPTH = 20;
    private static final int MAX_PROP_VALUE_LEN = 120;

    // Properties worth including for naming/context purposes
    private static final String[] USEFUL_PROP_KEYS = {
        "HTTPSampler.method", "HTTPSampler.path", "HTTPSampler.domain",
        "HTTPSampler.port", "HTTPSampler.protocol",
        "scriptLanguage", "script",
        "LoopController.loops", "IfController.condition",
        "ThreadGroup.num_threads", "ThreadGroup.ramp_time", "ThreadGroup.duration",
        "variableNames", "filename",
        "Assertion.test_strings", "JSONPostProcessor.referenceNames"
    };

    private static final String[] SKIP_TYPE_FRAGMENTS = {
        "CollectionProperty", "MapProperty", "TestElementProperty", "FunctionProperty"
    };

    private JMeterPlanSerializer() {}

    /**
     * Serializes the JMeter tree rooted at {@code root} to a compact JSON string.
     * The root node itself (Test Plan) is included as the first entry.
     *
     * @param root        root of the JMeter tree
     * @param maxElements maximum number of nodes to serialize
     * @param maxDepth    maximum depth to traverse (root = depth 0)
     * @return compact JSON string suitable for AI prompts
     */
    public static String toCompactJson(JMeterTreeNode root, int maxElements, int maxDepth) {
        List<Map<String, Object>> elements = collectElements(root, maxElements, maxDepth);
        return buildJson(elements, elements.size() >= maxElements);
    }

    public static String toCompactJson(JMeterTreeNode root) {
        return toCompactJson(root, DEFAULT_MAX_ELEMENTS, DEFAULT_MAX_DEPTH);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static List<Map<String, Object>> collectElements(
            JMeterTreeNode root, int maxElements, int maxDepth) {

        List<Map<String, Object>> result = new ArrayList<>();

        // Stack entries: [node, depth, id_counter_ref]
        // We use two parallel stacks for node and depth
        Deque<JMeterTreeNode> nodeStack = new ArrayDeque<>();
        Deque<Integer> depthStack = new ArrayDeque<>();

        nodeStack.push(root);
        depthStack.push(0);

        int id = 1;

        while (!nodeStack.isEmpty() && result.size() < maxElements) {
            JMeterTreeNode node = nodeStack.pop();
            int depth = depthStack.pop();

            if (depth > maxDepth) {
                continue;
            }

            TestElement element = node.getTestElement();
            if (element == null) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id++);
            entry.put("depth", depth);
            entry.put("type", element.getClass().getSimpleName());
            entry.put("name", node.getName());

            Map<String, String> props = extractUsefulProps(element);
            if (!props.isEmpty()) {
                entry.put("props", props);
            }

            result.add(entry);

            // Push children in reverse order so first child is processed first
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                Object child = node.getChildAt(i);
                if (child instanceof JMeterTreeNode) {
                    nodeStack.push((JMeterTreeNode) child);
                    depthStack.push(depth + 1);
                }
            }
        }

        return result;
    }

    private static Map<String, String> extractUsefulProps(TestElement element) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : USEFUL_PROP_KEYS) {
            try {
                JMeterProperty prop = element.getProperty(key);
                if (prop == null) continue;
                String typeName = prop.getClass().getSimpleName();
                if (shouldSkipPropertyType(typeName)) continue;
                String value = prop.getStringValue();
                if (value == null || value.trim().isEmpty()) continue;
                if (value.length() > MAX_PROP_VALUE_LEN) {
                    value = value.substring(0, MAX_PROP_VALUE_LEN) + "...";
                }
                result.put(key, value);
            } catch (Exception e) {
                log.debug("Skipping property {} due to error: {}", key, e.getMessage());
            }
        }
        return result;
    }

    private static boolean shouldSkipPropertyType(String typeName) {
        for (String fragment : SKIP_TYPE_FRAGMENTS) {
            if (typeName.contains(fragment)) return true;
        }
        return false;
    }

    private static String buildJson(List<Map<String, Object>> elements, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"elements\":[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(",");
            appendElementJson(sb, elements.get(i));
        }
        sb.append("]");
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendElementJson(StringBuilder sb, Map<String, Object> entry) {
        sb.append("{");
        sb.append("\"id\":").append(entry.get("id")).append(",");
        sb.append("\"depth\":").append(entry.get("depth")).append(",");
        sb.append("\"type\":\"").append(jsonEscape((String) entry.get("type"))).append("\",");
        sb.append("\"name\":\"").append(jsonEscape((String) entry.get("name"))).append("\"");
        Object propsObj = entry.get("props");
        if (propsObj instanceof Map) {
            Map<String, String> props = (Map<String, String>) propsObj;
            if (!props.isEmpty()) {
                sb.append(",\"props\":{");
                boolean first = true;
                for (Map.Entry<String, String> p : props.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(jsonEscape(p.getKey())).append("\":");
                    sb.append("\"").append(jsonEscape(p.getValue())).append("\"");
                }
                sb.append("}");
            }
        }
        sb.append("}");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
