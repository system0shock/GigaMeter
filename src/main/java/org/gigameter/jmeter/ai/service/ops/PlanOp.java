package org.gigameter.jmeter.ai.service.ops;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single parsed plan-mutation operation. Fields are populated per {@link OpType}; unused fields
 * stay {@code null}/empty. Immutable once built.
 *
 * <p>Field semantics by op:
 * <ul>
 *   <li>{@code set_property}: {@link #id}, {@link #key}, {@link #value}</li>
 *   <li>{@code add_element}: {@link #parentId}, {@link #elementType}, {@link #name} (opt),
 *       {@link #props} (opt), {@link #index} (opt)</li>
 *   <li>{@code remove_element}: {@link #id}</li>
 *   <li>{@code replace_subtree}: {@link #id}, {@link #element} (subtree definition)</li>
 *   <li>{@code new_test_plan}: {@link #element} (root definition, opt)</li>
 *   <li>{@code get_element}: {@link #id}</li>
 * </ul>
 */
public final class PlanOp {

    private final OpType op;
    private final Integer id;
    private final Integer parentId;
    private final Integer index;
    private final String key;
    private final String value;
    private final String elementType;
    private final String name;
    private final Map<String, String> props;
    private final JsonNode element;
    private final List<PlanOp> children;

    private PlanOp(Builder b) {
        this.op = b.op;
        this.id = b.id;
        this.parentId = b.parentId;
        this.index = b.index;
        this.key = b.key;
        this.value = b.value;
        this.elementType = b.elementType;
        this.name = b.name;
        this.props = b.props == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.props));
        this.element = b.element;
        this.children = b.children == null ? Collections.emptyList()
                : Collections.unmodifiableList(b.children);
    }

    public OpType op() { return op; }
    public Integer id() { return id; }
    public Integer parentId() { return parentId; }
    public Integer index() { return index; }
    public String key() { return key; }
    public String value() { return value; }
    public String elementType() { return elementType; }
    public String name() { return name; }
    public Map<String, String> props() { return props; }
    public JsonNode element() { return element; }
    /** Nested child elements to create under this one (for building subtrees in a single op). */
    public List<PlanOp> children() { return children; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(op == null ? "?" : op.wire());
        if (id != null) sb.append(" id=").append(id);
        if (parentId != null) sb.append(" parentId=").append(parentId);
        if (elementType != null) sb.append(" type=").append(elementType);
        if (key != null) sb.append(" key=").append(key);
        return sb.toString();
    }

    public static Builder builder(OpType op) {
        return new Builder(op);
    }

    public static final class Builder {
        private final OpType op;
        private Integer id;
        private Integer parentId;
        private Integer index;
        private String key;
        private String value;
        private String elementType;
        private String name;
        private Map<String, String> props;
        private JsonNode element;
        private List<PlanOp> children;

        private Builder(OpType op) { this.op = op; }

        public Builder id(Integer v) { this.id = v; return this; }
        public Builder parentId(Integer v) { this.parentId = v; return this; }
        public Builder index(Integer v) { this.index = v; return this; }
        public Builder key(String v) { this.key = v; return this; }
        public Builder value(String v) { this.value = v; return this; }
        public Builder elementType(String v) { this.elementType = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder props(Map<String, String> v) { this.props = v; return this; }
        public Builder element(JsonNode v) { this.element = v; return this; }
        public Builder children(List<PlanOp> v) { this.children = v; return this; }

        public PlanOp build() { return new PlanOp(this); }
    }
}
