package org.gigameter.jmeter.ai.service.ops;

/**
 * The set of test-plan mutation operations a CLI agent may emit inside a
 * {@code ```jmeter-ops} fenced block. Kept deliberately small and explicit so the
 * protocol is easy for a model to comply with and easy to validate strictly.
 */
public enum OpType {
    /** Set a single property on an existing element addressed by node id. */
    SET_PROPERTY("set_property"),
    /** Create a new element under a parent (by id) of a given element type. */
    ADD_ELEMENT("add_element"),
    /** Remove an existing element (and its subtree) addressed by node id. */
    REMOVE_ELEMENT("remove_element"),
    /** Replace the entire subtree under a node id with a new element definition. */
    REPLACE_SUBTREE("replace_subtree"),
    /** Start a brand-new test plan, discarding the current one (with confirmation). */
    NEW_TEST_PLAN("new_test_plan"),
    /** Read-only: ask for the full property dump of an element by id (self-correction aid). */
    GET_ELEMENT("get_element");

    private final String wire;

    OpType(String wire) {
        this.wire = wire;
    }

    /** The lowercase token used on the wire (in the JSON {@code "op"} field). */
    public String wire() {
        return wire;
    }

    /** Resolves a wire token to an {@link OpType}, or {@code null} if unknown. */
    public static OpType fromWire(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(java.util.Locale.ROOT);
        for (OpType op : values()) {
            if (op.wire.equals(t)) {
                return op;
            }
        }
        return null;
    }

    /** Read-only operations do not mutate the tree and need no preview/undo. */
    public boolean isReadOnly() {
        return this == GET_ELEMENT;
    }
}
