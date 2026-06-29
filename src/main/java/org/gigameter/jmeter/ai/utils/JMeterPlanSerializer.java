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
    public static final int SKELETON_MAX_ELEMENTS = 5000;
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

    /**
     * Resolves the node to serialize/edit from: the JMeter tree's {@code getRoot()} is a structural
     * container whose child is the actual Test Plan. Serializing from the container makes {@code #1}
     * a phantom and shifts every id, which confuses agents (they add Thread Groups under the phantom
     * → detached). This returns the Test Plan node so {@code #1} is the Test Plan. Falls back to the
     * given root if no Test Plan child is found. Both the prompt context and the apply engine must
     * use this same root so ids line up.
     */
    public static JMeterTreeNode planRoot(JMeterTreeNode root) {
        if (root == null) {
            return null;
        }
        JMeterTreeNode node = root;
        // JMeter's getRoot() can be a structural node above the visible Test Plan, and in practice the
        // root Test Plan itself wraps another Test Plan node (observed #1 → #2, both TestPlan). Descend
        // to the DEEPEST Test Plan so the visible plan is the serialization root and ids line up.
        if (!isTestPlan(node)) {
            JMeterTreeNode tp = firstTestPlanChild(node);
            if (tp != null) {
                node = tp;
            }
        }
        while (isTestPlan(node)) {
            JMeterTreeNode childTp = firstTestPlanChild(node);
            if (childTp == null) {
                break;
            }
            node = childTp;
        }
        return node;
    }

    private static JMeterTreeNode firstTestPlanChild(JMeterTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode && isTestPlan((JMeterTreeNode) child)) {
                return (JMeterTreeNode) child;
            }
        }
        return null;
    }

    private static boolean isTestPlan(JMeterTreeNode node) {
        TestElement el = node.getTestElement();
        return el != null && el.getClass().getSimpleName().equals("TestPlan");
    }

    /** Convenience: returns just the compact JSON string. */
    public static String toCompactJson(JMeterTreeNode root, int maxElements, int maxDepth) {
        return serialize(root, maxElements, maxDepth).toJson();
    }

    public static String toCompactJson(JMeterTreeNode root) {
        return serialize(root).toJson();
    }

    // =========================================================================
    // Public helpers (used by PlanSkeleton, PlanContextBuilder, etc.)
    // =========================================================================

    /** Exclusive end index of the subtree rooted at {@code idx} (preorder + depth list). */
    public static int subtreeEnd(List<ElementEntry> elements, int idx) {
        int depth = elements.get(idx).depth;
        int j = idx + 1;
        while (j < elements.size() && elements.get(j).depth > depth) {
            j++;
        }
        return j;
    }

    /** Public access to the friendly label mapping (used by PlanSkeleton). */
    public static String friendlyType(String type) {
        return friendlyTypeName(type);
    }

    /** Public access to the inline prop summary (used by PlanSkeleton representatives). */
    public static String inlineSummary(String type, Map<String, String> props) {
        return inlineProps(type, props);
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

        /**
         * Renders the plan as a human-readable indented tree for AI prompts.
         * Converts JMeter class names to plain labels and formats key props inline.
         * Each line is prefixed with {@code #<id>} — the same id present in {@link #nodeById},
         * which CLI agents reference in {@code jmeter-ops} operations. Much easier for LLMs to
         * interpret than raw JSON.
         */
        public String toReadableTree() {
            return buildReadableTree(elements, truncated);
        }

        /** Readable tree (full inline props) over the sublist {@code [fromIdx, toIdx)}. */
        public String toReadableTree(int fromIdx, int toIdx) {
            List<ElementEntry> sub = elements.subList(
                    Math.max(0, fromIdx),
                    Math.min(toIdx, elements.size()));
            return buildReadableTree(sub, false);
        }

        /**
         * A stable fingerprint of the plan's structure (ids, types, names, and tracked props). Used
         * to detect that the tree changed under the agent between context export and op apply, so a
         * stale node id can be rejected with "re-fetch tree" instead of editing the wrong element.
         */
        public String revisionHash() {
            StringBuilder sb = new StringBuilder();
            for (ElementEntry e : elements) {
                sb.append(e.id).append('|').append(e.depth).append('|')
                  .append(e.type).append('|').append(e.name).append('|')
                  .append(e.props).append('\n');
            }
            return Integer.toHexString(sb.toString().hashCode());
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

    // =========================================================================
    // Human-readable tree rendering
    // =========================================================================

    private static String buildReadableTree(List<ElementEntry> entries, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append("Структура JMeter тест-плана (дерево элементов, отступы = вложенность; "
                + "#N — id элемента для операций jmeter-ops):\n");
        for (ElementEntry e : entries) {
            String indent = "  ".repeat(Math.max(0, e.depth));
            String label = friendlyTypeName(e.type);
            String inline = inlineProps(e.type, e.props);
            sb.append("#").append(e.id).append(" ").append(indent)
              .append("└─ [").append(label).append("] \"").append(e.name).append("\"");
            if (!inline.isEmpty()) sb.append(" | ").append(inline);
            sb.append("\n");
        }
        if (truncated) sb.append("(список обрезан, показаны первые элементы)\n");
        return sb.toString();
    }

    private static String friendlyTypeName(String type) {
        if (type == null) return "Element";
        if (type.contains("ThreadGroup"))         return "Thread Group";
        if (type.contains("HTTPSampler"))         return "HTTP Sampler";
        if (type.contains("JSR223Sampler"))       return "JSR223 Sampler";
        if (type.contains("JSR223PreProcessor"))  return "JSR223 PreProcessor";
        if (type.contains("JSR223PostProcessor")) return "JSR223 PostProcessor";
        if (type.contains("JavaSampler"))         return "Java Sampler";
        if (type.contains("TransactionController")) return "Transaction Controller";
        if (type.contains("IfController"))        return "If Controller";
        if (type.contains("WhileController"))     return "While Controller";
        if (type.contains("ForeachController"))   return "ForEach Controller";
        if (type.contains("LoopController"))      return "Loop Controller";
        if (type.contains("GenericSampler"))      return "Generic Sampler";
        if (type.contains("ResponseAssertion"))   return "Response Assertion";
        if (type.contains("JSONPathAssertion"))   return "JSONPath Assertion";
        if (type.contains("JSONPostProcessor"))   return "JSON Extractor";
        if (type.contains("RegexExtractor"))      return "Regex Extractor";
        if (type.contains("HeaderManager"))       return "Header Manager";
        if (type.contains("CookieManager"))       return "Cookie Manager";
        if (type.contains("CacheManager"))        return "Cache Manager";
        if (type.contains("CSVDataSet"))          return "CSV Data Set";
        if (type.contains("ConstantTimer"))       return "Constant Timer";
        if (type.contains("UniformRandomTimer"))  return "Random Timer";
        if (type.contains("ResultCollector"))     return "Listener";
        if (type.contains("DebugSampler"))        return "Debug Sampler";
        return type; // fallback: raw class name
    }

    private static String inlineProps(String type, Map<String, String> props) {
        if (props == null || props.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (type != null && type.contains("HTTPSampler")) {
            String method = props.get("HTTPSampler.method");
            String path   = props.get("HTTPSampler.path");
            String domain = props.get("HTTPSampler.domain");
            if (method != null) sb.append(method).append(" ");
            if (domain != null && !domain.isEmpty()) sb.append(domain);
            if (path   != null && !path.isEmpty())   sb.append(path);
        } else if (type != null && type.contains("ThreadGroup")) {
            String users   = props.get("ThreadGroup.num_threads");
            String ramp    = props.get("ThreadGroup.ramp_time");
            String duration = props.get("ThreadGroup.duration");
            if (users != null)    sb.append("users=").append(users);
            if (ramp  != null)    sb.append(", ramp=").append(ramp).append("s");
            if (duration != null && !duration.equals("0")) sb.append(", duration=").append(duration).append("s");
        } else if (type != null && (type.contains("JSR223") || type.contains("jsr223"))) {
            String lang = props.get("scriptLanguage");
            if (lang != null) sb.append("lang=").append(lang);
        } else if (type != null && type.contains("IfController")) {
            String cond = props.get("IfController.condition");
            if (cond != null) sb.append("if: ").append(cond.length() > 60 ? cond.substring(0, 60) + "..." : cond);
        } else if (type != null && type.contains("WhileController")) {
            String cond = props.get("WhileController.condition");
            if (cond == null) cond = props.getOrDefault("IfController.condition", null);
            if (cond != null) sb.append("while: ").append(cond.length() > 60 ? cond.substring(0, 60) + "..." : cond);
        } else if (type != null && type.contains("LoopController")) {
            String loops = props.get("LoopController.loops");
            if (loops != null) sb.append("loops=").append(loops);
        } else if (type != null && type.contains("CSVDataSet")) {
            String file = props.get("filename");
            String vars = props.get("variableNames");
            if (file != null) sb.append("file=").append(file);
            if (vars != null) sb.append(", vars=").append(vars);
        } else if (type != null && type.contains("JSONPostProcessor")) {
            String ref = props.get("JSONPostProcessor.referenceNames");
            if (ref != null) sb.append("→ ").append(ref);
        }
        return sb.toString().trim();
    }
}
