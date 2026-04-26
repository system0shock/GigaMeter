package org.gigameter.jmeter.ai.utils;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a JMeter tree into a compact JSON representation.
 * Uses iterative traversal to avoid stack overflow on deep plans.
 *
 * Primary entry point: {@link #serialize(JMeterTreeNode, int, int)} which
 * returns a {@link SerializedPlan} with the element list, a node-by-id
 * lookup map, and helpers for building JSON prompts (including sub-range
 * slices for batching).
 */
public class JMeterPlanSerializer {
    private static final Logger log = LoggerFactory.getLogger(JMeterPlanSerializer.class);

    public static final int DEFAULT_MAX_ELEMENTS = 300;
    public static final int DEFAULT_MAX_DEPTH = 20;
    private static final int MAX_PROP_VALUE_LEN = 120;

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

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Serializes the JMeter tree rooted at {@code root} into a {@link SerializedPlan}.
     * IDs are assigned sequentially starting from 1 in DFS order.
     */
    public static SerializedPlan serialize(JMeterTreeNode root, int maxElements, int maxDepth) {
        List<ElementEntry> entries = new ArrayList<>();
        Map<Integer, JMeterTreeNode> nodeById = new LinkedHashMap<>();
        boolean truncated = collectEntries(root, maxElements, maxDepth, entries, nodeById);
        return new SerializedPlan(entries, nodeById, truncated);
    }

    public static SerializedPlan serialize(JMeterTreeNode root) {
        return serialize(root, DEFAULT_MAX_ELEMENTS, DEFAULT_MAX_DEPTH);
    }

    /** Convenience: returns just the compact JSON string. */
    public static String toCompactJson(JMeterTreeNode root, int maxElements, int maxDepth) {
        return serialize(root, maxElements, maxDepth).toJson();
    }

    public static String toCompactJson(JMeterTreeNode root) {
        return serialize(root).toJson();
    }

    // =========================================================================
    // SerializedPlan
    // =========================================================================

    public static final class SerializedPlan {
        public final List<ElementEntry> elements;
        public final Map<Integer, JMeterTreeNode> nodeById;
        public final boolean truncated;

        public SerializedPlan(List<ElementEntry> elements,
                       Map<Integer, JMeterTreeNode> nodeById,
                       boolean truncated) {
            this.elements = Collections.unmodifiableList(elements);
            this.nodeById = Collections.unmodifiableMap(nodeById);
            this.truncated = truncated;
        }

        /** Full JSON of all elements. */
        public String toJson() {
            return buildJson(elements, truncated);
        }

        /** JSON of a subrange [fromIdx, toIdx) — used for batching. */
        public String toJson(int fromIdx, int toIdx) {
            List<ElementEntry> sub = elements.subList(
                    Math.max(0, fromIdx),
                    Math.min(toIdx, elements.size()));
            return buildJson(sub, false);
        }

        public int size() {
            return elements.size();
        }
    }

    // =========================================================================
    // ElementEntry
    // =========================================================================

    public static final class ElementEntry {
        public final int id;
        public final int depth;
        public final String type;
        public final String name;
        public final Map<String, String> props;

        public ElementEntry(int id, int depth, String type, String name, Map<String, String> props) {
            this.id = id;
            this.depth = depth;
            this.type = type;
            this.name = name;
            this.props = Collections.unmodifiableMap(props);
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static boolean collectEntries(JMeterTreeNode root, int maxElements, int maxDepth,
                                          List<ElementEntry> result,
                                          Map<Integer, JMeterTreeNode> nodeById) {
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

            Map<String, String> props = extractUsefulProps(element);
            ElementEntry entry = new ElementEntry(id, depth,
                    element.getClass().getSimpleName(), node.getName(), props);
            result.add(entry);
            nodeById.put(id, node);
            id++;

            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                Object child = node.getChildAt(i);
                if (child instanceof JMeterTreeNode) {
                    nodeStack.push((JMeterTreeNode) child);
                    depthStack.push(depth + 1);
                }
            }
        }

        return !nodeStack.isEmpty(); // truncated if nodes remain
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

    private static String buildJson(List<ElementEntry> entries, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"elements\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            appendEntryJson(sb, entries.get(i));
        }
        sb.append("]");
        if (truncated) sb.append(",\"truncated\":true");
        sb.append("}");
        return sb.toString();
    }

    private static void appendEntryJson(StringBuilder sb, ElementEntry e) {
        sb.append("{\"id\":").append(e.id)
          .append(",\"depth\":").append(e.depth)
          .append(",\"type\":\"").append(jsonEscape(e.type)).append("\"")
          .append(",\"name\":\"").append(jsonEscape(e.name)).append("\"");
        if (!e.props.isEmpty()) {
            sb.append(",\"props\":{");
            boolean first = true;
            for (Map.Entry<String, String> p : e.props.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(jsonEscape(p.getKey())).append("\":")
                  .append("\"").append(jsonEscape(p.getValue())).append("\"");
            }
            sb.append("}");
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
