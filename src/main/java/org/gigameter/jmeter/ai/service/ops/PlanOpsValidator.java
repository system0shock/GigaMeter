package org.gigameter.jmeter.ai.service.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic validation of parsed {@link PlanOp}s: each op must carry the fields its {@link OpType}
 * requires, ids must be positive, and string fields must be non-blank. Collects every problem (not
 * just the first) so the agent can be corrected in one round-trip.
 */
public final class PlanOpsValidator {

    private PlanOpsValidator() {
    }

    /** @throws OpsException if any op is semantically incomplete; the message lists all problems. */
    public static void validate(List<PlanOp> ops) throws OpsException {
        List<String> problems = collectProblems(ops);
        if (!problems.isEmpty()) {
            throw new OpsException("Операции jmeter-ops не прошли валидацию", problems);
        }
    }

    /** Non-throwing variant: returns the (possibly empty) list of problems. */
    public static List<String> collectProblems(List<PlanOp> ops) {
        List<String> problems = new ArrayList<>();
        if (ops == null || ops.isEmpty()) {
            problems.add("Список операций пуст");
            return problems;
        }
        for (int i = 0; i < ops.size(); i++) {
            PlanOp op = ops.get(i);
            String prefix = "Операция #" + i + " (" + (op.op() == null ? "?" : op.op().wire()) + "): ";
            switch (op.op()) {
                case SET_PROPERTY:
                    requirePositiveId(op.id(), prefix, problems);
                    requireText(op.key(), "key", prefix, problems);
                    if (op.value() == null) {
                        problems.add(prefix + "отсутствует поле \"value\"");
                    }
                    break;
                case ADD_ELEMENT:
                    requirePositiveId(op.parentId(), prefix + "parentId — ", problems);
                    requireText(op.elementType(), "elementType", prefix, problems);
                    validateChildren(op, prefix, problems);
                    break;
                case REMOVE_ELEMENT:
                case GET_ELEMENT:
                    requirePositiveId(op.id(), prefix, problems);
                    break;
                case REPLACE_SUBTREE:
                    requirePositiveId(op.id(), prefix, problems);
                    if (op.element() == null || op.element().isNull()) {
                        problems.add(prefix + "отсутствует поле \"element\" с описанием поддерева");
                    }
                    break;
                case NEW_TEST_PLAN:
                    // No required fields: an empty new_test_plan creates a bare Test Plan.
                    break;
                default:
                    problems.add(prefix + "неподдерживаемая операция");
            }
        }
        return problems;
    }

    /** Recursively validates nested children: each needs an elementType; parent is implicit. */
    private static void validateChildren(PlanOp op, String prefix, List<String> problems) {
        for (PlanOp child : op.children()) {
            String cp = prefix + "child[" + child.elementType() + "]: ";
            requireText(child.elementType(), "elementType", cp, problems);
            validateChildren(child, cp, problems);
        }
    }

    private static void requirePositiveId(Integer id, String prefix, List<String> problems) {
        if (id == null) {
            problems.add(prefix + "отсутствует \"id\"");
        } else if (id <= 0) {
            problems.add(prefix + "\"id\" должен быть положительным (получено " + id + ")");
        }
    }

    private static void requireText(String v, String field, String prefix, List<String> problems) {
        if (v == null || v.trim().isEmpty()) {
            problems.add(prefix + "отсутствует или пустое поле \"" + field + "\"");
        }
    }
}
