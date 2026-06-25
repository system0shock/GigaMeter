package org.gigameter.jmeter.ai.service.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a {@code jmeter-ops} JSON payload into {@link PlanOp} objects. Strict by design: the root
 * must be a JSON array, every entry must be an object with a known {@code op}, and only the field
 * names defined by the protocol are accepted. Anything else throws an {@link OpsException} carrying
 * the specific problems, which the chat layer can echo back to the agent for self-correction.
 *
 * <p>Semantic completeness (e.g. {@code set_property} needing {@code key}) is the job of
 * {@link PlanOpsValidator}; this parser only enforces shape and field whitelisting.
 */
public final class PlanOpsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every field name the protocol recognises across all ops. */
    private static final Set<String> ALLOWED_FIELDS = Collections.unmodifiableSet(
            new java.util.HashSet<>(Arrays.asList(
                    "op", "id", "parentId", "index", "key", "value",
                    "elementType", "name", "props", "element", "children")));

    private PlanOpsParser() {
    }

    public static List<PlanOp> parse(String json) throws OpsException {
        if (json == null || json.trim().isEmpty()) {
            throw new OpsException("Пустой блок операций jmeter-ops");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            // Streams/LLMs sometimes truncate the trailing brackets — try to repair and re-parse once.
            try {
                root = MAPPER.readTree(JsonRepair.closeTruncated(json));
            } catch (Exception e2) {
                throw new OpsException("Некорректный JSON в блоке jmeter-ops: " + e.getMessage(), e);
            }
        }
        // Tolerate a single op object or an {"ops": [...]} wrapper, but canonical form is an array.
        if (root.isObject() && root.has("ops")) {
            root = root.get("ops");
        }
        if (root.isObject() && root.has("op")) {
            List<PlanOp> single = new ArrayList<>(1);
            single.add(parseOne(root, 0, false));
            return single;
        }
        if (!root.isArray()) {
            throw new OpsException("Блок jmeter-ops должен быть JSON-массивом операций");
        }
        List<String> problems = new ArrayList<>();
        List<PlanOp> ops = new ArrayList<>();
        int i = 0;
        for (Iterator<JsonNode> it = root.elements(); it.hasNext(); i++) {
            JsonNode node = it.next();
            try {
                ops.add(parseOne(node, i, false));
            } catch (OpsException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new OpsException("Не удалось разобрать операции jmeter-ops", problems);
        }
        // An empty array is valid: the agent emitted a block but has nothing to change. Callers
        // treat an empty list as "no operations" (an informational answer), not an error.
        return ops;
    }

    /**
     * Parses a single element spec (as used in {@code replace_subtree}'s {@code element} field):
     * an implicit {@code add_element} with optional {@code props} and nested {@code children}.
     * Returns {@code null} if the node is null/blank.
     */
    public static PlanOp parseElementSpec(JsonNode node) throws OpsException {
        if (node == null || node.isNull()) {
            return null;
        }
        return parseOne(node, 0, true);
    }

    private static PlanOp parseOne(JsonNode node, int index, boolean child) throws OpsException {
        if (node == null || !node.isObject()) {
            throw new OpsException("Операция #" + index + " должна быть JSON-объектом");
        }
        // Reject unknown fields up front so typos don't silently no-op.
        for (Iterator<String> fn = node.fieldNames(); fn.hasNext(); ) {
            String field = fn.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new OpsException("Операция #" + index + ": неизвестное поле \"" + field + "\"");
            }
        }
        JsonNode opNode = node.get("op");
        OpType type;
        if (opNode == null || opNode.isNull()) {
            // Nested children are implicitly add_element under their parent.
            if (!child) {
                throw new OpsException("Операция #" + index + ": отсутствует строковое поле \"op\"");
            }
            type = OpType.ADD_ELEMENT;
        } else if (!opNode.isTextual()) {
            throw new OpsException("Операция #" + index + ": поле \"op\" должно быть строкой");
        } else {
            type = OpType.fromWire(opNode.asText());
            if (type == null) {
                throw new OpsException("Операция #" + index + ": неизвестная операция \"" + opNode.asText() + "\"");
            }
        }

        PlanOp.Builder b = PlanOp.builder(type);
        b.id(intOrNull(node, "id", index));
        b.parentId(intOrNull(node, "parentId", index));
        b.index(intOrNull(node, "index", index));
        b.key(textOrNull(node, "key"));
        b.value(scalarText(node.get("value")));
        b.elementType(textOrNull(node, "elementType"));
        b.name(textOrNull(node, "name"));
        b.props(props(node.get("props"), index));
        if (node.has("element")) {
            b.element(node.get("element"));
        }
        JsonNode childrenNode = node.get("children");
        if (childrenNode != null && childrenNode.isArray()) {
            List<PlanOp> kids = new ArrayList<>();
            int ci = 0;
            for (Iterator<JsonNode> it = childrenNode.elements(); it.hasNext(); ci++) {
                kids.add(parseOne(it.next(), ci, true));
            }
            b.children(kids);
        }
        return b.build();
    }

    private static Integer intOrNull(JsonNode node, String field, int index) throws OpsException {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isInt() || n.isLong()) {
            return n.asInt();
        }
        if (n.isTextual()) {
            try {
                return Integer.parseInt(n.asText().trim());
            } catch (NumberFormatException ignored) {
                // fall through to error
            }
        }
        throw new OpsException("Операция #" + index + ": поле \"" + field + "\" должно быть целым числом");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    /** Coerces a scalar JSON value (string/number/boolean) to its string form. */
    private static String scalarText(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return n.toString();
    }

    private static Map<String, String> props(JsonNode n, int index) throws OpsException {
        if (n == null || n.isNull()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        if (n.isObject()) {
            collectObjectProps(n, map);
            return map;
        }
        // Models often emit props as an array of single-entry objects, or as {key,value} pairs.
        // Accept both rather than reject — schema variance is common across CLI agents.
        if (n.isArray()) {
            for (Iterator<JsonNode> it = n.elements(); it.hasNext(); ) {
                JsonNode el = it.next();
                if (el == null || !el.isObject()) {
                    throw new OpsException("Операция #" + index + ": элементы \"props\" должны быть объектами");
                }
                if (el.has("key") && (el.has("value") || el.size() == 2)) {
                    map.put(el.get("key").asText(), scalarText(el.get("value")));
                } else {
                    collectObjectProps(el, map);
                }
            }
            return map;
        }
        throw new OpsException("Операция #" + index + ": поле \"props\" должно быть объектом key→value");
    }

    private static void collectObjectProps(JsonNode obj, Map<String, String> into) {
        for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            into.put(e.getKey(), scalarText(e.getValue()));
        }
    }
}
