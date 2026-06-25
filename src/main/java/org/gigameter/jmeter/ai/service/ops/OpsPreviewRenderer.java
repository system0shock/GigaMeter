package org.gigameter.jmeter.ai.service.ops;

import java.util.List;

/**
 * Renders a parsed, validated {@link PlanOp} batch into a short human-readable preview shown to the
 * user before any mutation is applied. Pure string work — no JMeter dependency — so it is unit
 * testable and safe to call off the EDT.
 */
public final class OpsPreviewRenderer {

    private OpsPreviewRenderer() {
    }

    public static String render(List<PlanOp> ops) {
        if (ops == null || ops.isEmpty()) {
            return "Нет операций для применения.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Агент предлагает изменения в тест-плане:\n");
        int n = 1;
        for (PlanOp op : ops) {
            sb.append(n++).append(". ").append(describe(op)).append("\n");
            appendChildren(sb, op, "    ");
        }
        boolean readOnlyOnly = ops.stream().allMatch(o -> o.op() != null && o.op().isReadOnly());
        if (!readOnlyOnly) {
            sb.append("\nПрименить? (изменения можно откатить кнопкой «Откатить изменение агента»)");
        }
        return sb.toString();
    }

    private static void appendChildren(StringBuilder sb, PlanOp op, String indent) {
        for (PlanOp child : op.children()) {
            sb.append(indent).append("└─ [").append(child.elementType()).append("]");
            if (child.name() != null) {
                sb.append(" \"").append(child.name()).append("\"");
            }
            sb.append("\n");
            appendChildren(sb, child, indent + "    ");
        }
    }

    private static String describe(PlanOp op) {
        if (op.op() == null) {
            return "неизвестная операция";
        }
        switch (op.op()) {
            case SET_PROPERTY:
                return "Изменить свойство #" + op.id() + ": " + op.key() + " = " + op.value();
            case ADD_ELEMENT:
                return "Добавить элемент [" + op.elementType() + "]"
                        + (op.name() != null ? " \"" + op.name() + "\"" : "")
                        + " в #" + op.parentId()
                        + (op.props().isEmpty() ? "" : " (" + op.props().size() + " свойств)");
            case REMOVE_ELEMENT:
                return "Удалить элемент #" + op.id() + " (вместе с дочерними)";
            case REPLACE_SUBTREE:
                return "Заменить поддерево #" + op.id();
            case NEW_TEST_PLAN:
                return "Создать новый тест-план (текущий будет очищен)";
            case GET_ELEMENT:
                return "Запросить детали элемента #" + op.id() + " (только чтение)";
            default:
                return op.op().wire();
        }
    }
}
