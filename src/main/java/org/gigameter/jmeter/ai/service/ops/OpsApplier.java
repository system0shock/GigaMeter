package org.gigameter.jmeter.ai.service.ops;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.gigameter.jmeter.ai.utils.JMeterElementManager;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies a validated {@link PlanOp} batch to the live JMeter tree. All tree access happens on the
 * Swing EDT (via {@link SwingUtilities#invokeAndWait}); node ids are resolved against a fresh
 * serialization so they line up with what the agent saw, and a {@code revision} guard rejects edits
 * if the tree changed underneath. Every mutating op records its inverse in {@link OpsUndoStore} so
 * the whole batch can be rolled back as a unit.
 *
 * <p><b>Note:</b> this class can only be exercised inside a running JMeter GUI; it is integration
 * code, not unit-tested. The protocol parsing/validation/preview layers around it are unit-tested.
 */
public final class OpsApplier {

    private static final Logger log = LoggerFactory.getLogger(OpsApplier.class);
    private static final int RESOLVE_MAX_ELEMENTS = 2000;
    private static final int RESOLVE_MAX_DEPTH = 50;

    private OpsApplier() {
    }

    /**
     * @param ops              validated operations
     * @param expectedRevision revision hash the agent was shown, or {@code null} to skip the guard
     */
    public static OpsApplyResult apply(List<PlanOp> ops, String expectedRevision) {
        AtomicReference<OpsApplyResult> ref = new AtomicReference<>();
        Runnable body = () -> ref.set(applyOnEdt(ops, expectedRevision));
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                OpsApplyResult r = new OpsApplyResult();
                r.recordFailure("Применение прервано");
                return r;
            } catch (InvocationTargetException e) {
                log.error("Ops apply failed on EDT", e);
                OpsApplyResult r = new OpsApplyResult();
                r.recordFailure("Внутренняя ошибка применения: " + e.getCause());
                return r;
            }
        }
        return ref.get();
    }

    private static OpsApplyResult applyOnEdt(List<PlanOp> ops, String expectedRevision) {
        OpsApplyResult result = new OpsApplyResult();
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null || guiPackage.getTreeModel() == null) {
            result.recordFailure("JMeter GUI недоступен");
            return result;
        }
        JMeterTreeModel treeModel = guiPackage.getTreeModel();
        Object rootObj = treeModel.getRoot();
        if (!(rootObj instanceof JMeterTreeNode)) {
            result.recordFailure("Не найден корень тест-плана");
            return result;
        }
        // Resolve ids from the Test Plan node (same as the prompt context), not the structural root,
        // so the agent's #ids line up with what it was shown.
        JMeterTreeNode root = JMeterPlanSerializer.planRoot((JMeterTreeNode) rootObj);

        JMeterPlanSerializer.SerializedPlan plan =
                JMeterPlanSerializer.serialize(root, RESOLVE_MAX_ELEMENTS, RESOLVE_MAX_DEPTH);
        if (expectedRevision != null && !expectedRevision.equals(plan.revisionHash())) {
            result.markStaleTree();
            return result;
        }
        // Mutable copy so elements created earlier in the batch can be registered under their
        // predicted ids (the agent assumes new nodes get the next sequential ids), letting a single
        // batch both create an element and populate it.
        Map<Integer, JMeterTreeNode> nodeById = new java.util.LinkedHashMap<>(plan.nodeById);
        int[] nextId = {nodeById.size() + 1};

        OpsUndoStore.beginBatch();
        try {
            for (PlanOp op : ops) {
                try {
                    applyOne(op, nodeById, nextId, treeModel, result);
                } catch (RuntimeException e) {
                    result.recordFailure(op + " — " + e.getMessage());
                }
            }
        } finally {
            OpsUndoStore.endBatch();
        }

        if (result.isMutated()) {
            treeModel.nodeStructureChanged(root);
            if (guiPackage.getMainFrame() != null) {
                guiPackage.getMainFrame().repaint();
            }
        }
        return result;
    }

    private static void applyOne(PlanOp op, Map<Integer, JMeterTreeNode> nodeById, int[] nextId,
                                 JMeterTreeModel treeModel, OpsApplyResult result) {
        switch (op.op()) {
            case GET_ELEMENT: {
                JMeterTreeNode node = require(nodeById, op.id());
                result.recordRead(dumpElement(op.id(), node));
                break;
            }
            case SET_PROPERTY: {
                JMeterTreeNode node = require(nodeById, op.id());
                TestElement el = node.getTestElement();
                if (isVirtualKey(op.key())) {
                    // Collection property (body/param/header/assert) — built by the configurer.
                    // No precise undo for these; the batch undo still removes added elements.
                    OpsElementConfigurer.configure(el, java.util.Collections.singletonMap(op.key(), op.value()));
                } else {
                    JMeterProperty existing = el.getProperty(op.key());
                    final String oldValue = existing == null ? null : existing.getStringValue();
                    el.setProperty(op.key(), op.value());
                    OpsUndoStore.record(() -> {
                        if (oldValue == null) {
                            el.removeProperty(op.key());
                        } else {
                            el.setProperty(op.key(), oldValue);
                        }
                    });
                }
                result.recordApplied("#" + op.id() + ": " + op.key() + " = " + op.value());
                break;
            }
            case ADD_ELEMENT: {
                JMeterTreeNode parent = require(nodeById, op.parentId());
                String type = ElementTypeAlias.normalize(op.elementType());
                String name = op.name() == null || op.name().trim().isEmpty()
                        ? op.elementType() : op.name();
                if (!JMeterElementManager.addElementToNode(type, name, parent)) {
                    result.recordFailure("Не удалось добавить [" + op.elementType() + "] в #" + op.parentId()
                            + " (несовместимый родитель или неизвестный тип)");
                    break;
                }
                JMeterTreeNode added = lastChild(parent);
                if (added != null) {
                    OpsElementConfigurer.configure(added.getTestElement(), op.props());
                    final JMeterTreeNode toRemove = added;
                    OpsUndoStore.record(() -> treeModel.removeNodeFromParent(toRemove));
                    // Register under the next predicted id so a later op in this batch can target it.
                    nodeById.put(nextId[0]++, added);
                    // Recursively create nested children under this element (deterministic nesting,
                    // no id-prediction needed — the agent describes the subtree structurally).
                    int nested = addChildren(added, op.children(), nodeById, nextId, result);
                    result.recordApplied("добавлен [" + op.elementType() + "] \"" + name + "\" в #" + op.parentId()
                            + (nested > 0 ? " (+" + nested + " вложенных)" : ""));
                } else {
                    result.recordApplied("добавлен [" + op.elementType() + "] \"" + name + "\" в #" + op.parentId());
                }
                break;
            }
            case REMOVE_ELEMENT: {
                JMeterTreeNode node = require(nodeById, op.id());
                JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
                if (parent == null) {
                    result.recordFailure("#" + op.id() + ": нельзя удалить корневой элемент");
                    break;
                }
                final int index = parent.getIndex(node);
                treeModel.removeNodeFromParent(node);
                OpsUndoStore.record(() -> treeModel.insertNodeInto(node, parent, Math.max(0, index)));
                result.recordApplied("удалён #" + op.id());
                break;
            }
            case REPLACE_SUBTREE: {
                JMeterTreeNode node = require(nodeById, op.id());
                PlanOp spec;
                try {
                    spec = PlanOpsParser.parseElementSpec(op.element());
                } catch (OpsException e) {
                    result.recordFailure("#" + op.id() + ": некорректный element — " + e.getMessage());
                    break;
                }
                if (spec == null) {
                    result.recordFailure("#" + op.id() + ": отсутствует element");
                    break;
                }
                // Agents sometimes try to replace_subtree the whole Test Plan to "build" it — that
                // would delete the plan. Instead, populate the existing Test Plan with the spec's
                // children (non-destructive).
                if (isTestPlan(node)) {
                    int nested = addChildren(node, spec.children(), nodeById, nextId, result);
                    result.recordApplied("в Test Plan добавлено вложенных: " + nested);
                    break;
                }
                JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
                if (parent == null) {
                    result.recordFailure("#" + op.id() + ": нельзя заменить корневой элемент");
                    break;
                }
                String type = ElementTypeAlias.normalize(spec.elementType());
                if (type.isEmpty()) {
                    result.recordFailure("#" + op.id() + ": в element не указан elementType");
                    break;
                }
                final int index = parent.getIndex(node);
                String name = spec.name() == null || spec.name().trim().isEmpty() ? type : spec.name();
                treeModel.removeNodeFromParent(node);
                OpsUndoStore.record(() -> treeModel.insertNodeInto(node, parent, Math.max(0, index)));
                if (JMeterElementManager.addElementToNode(type, name, parent)) {
                    JMeterTreeNode added = lastChild(parent);
                    if (added != null) {
                        OpsElementConfigurer.configure(added.getTestElement(), spec.props());
                        final JMeterTreeNode toRemove = added;
                        OpsUndoStore.record(() -> treeModel.removeNodeFromParent(toRemove));
                        nodeById.put(nextId[0]++, added);
                        addChildren(added, spec.children(), nodeById, nextId, result);
                    }
                    result.recordApplied("заменено поддерево #" + op.id() + " на [" + type + "]");
                } else {
                    result.recordFailure("#" + op.id() + ": не удалось создать [" + type + "]");
                }
                break;
            }
            case NEW_TEST_PLAN:
                // Clearing the whole plan from a CLI edit is destructive and not yet covered by undo;
                // steer the user to the dedicated @plan flow rather than silently wiping their tree.
                result.recordFailure("new_test_plan через CLI не поддерживается в этой версии — "
                        + "используйте команду @plan");
                break;
            default:
                result.recordFailure("неподдерживаемая операция: " + op.op());
        }
    }

    /**
     * Recursively creates {@code children} under {@code parent} (depth-first), configuring props and
     * registering each under a predicted id. Returns the total number of descendants created.
     */
    private static int addChildren(JMeterTreeNode parent, List<PlanOp> children,
                                   Map<Integer, JMeterTreeNode> nodeById, int[] nextId,
                                   OpsApplyResult result) {
        int count = 0;
        for (PlanOp child : children) {
            String type = ElementTypeAlias.normalize(child.elementType());
            String name = child.name() == null || child.name().trim().isEmpty()
                    ? child.elementType() : child.name();
            if (!JMeterElementManager.addElementToNode(type, name, parent)) {
                result.recordFailure("Не удалось добавить вложенный [" + child.elementType()
                        + "] в \"" + parent.getName() + "\"");
                continue;
            }
            JMeterTreeNode added = lastChild(parent);
            if (added == null) {
                continue;
            }
            OpsElementConfigurer.configure(added.getTestElement(), child.props());
            nodeById.put(nextId[0]++, added);
            count++;
            count += addChildren(added, child.children(), nodeById, nextId, result);
        }
        return count;
    }

    private static boolean isTestPlan(JMeterTreeNode node) {
        return node != null && node.getTestElement() != null
                && "TestPlan".equals(node.getTestElement().getClass().getSimpleName());
    }

    private static JMeterTreeNode require(Map<Integer, JMeterTreeNode> nodeById, Integer id) {
        JMeterTreeNode node = id == null ? null : nodeById.get(id);
        if (node == null) {
            throw new IllegalArgumentException("устаревший или неизвестный id #" + id + " — обновите дерево");
        }
        return node;
    }

    /** A virtual prop key handled by {@link OpsElementConfigurer} (collection property), not a scalar. */
    private static boolean isVirtualKey(String key) {
        if (key == null) {
            return false;
        }
        return "body".equalsIgnoreCase(key) || key.startsWith("param.")
                || key.startsWith("header.") || key.startsWith("assert.")
                || "utg.schedule".equalsIgnoreCase(key) || "tst.schedule".equalsIgnoreCase(key);
    }

    private static JMeterTreeNode lastChild(JMeterTreeNode parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }
        return (JMeterTreeNode) parent.getChildAt(parent.getChildCount() - 1);
    }

    private static String dumpElement(Integer id, JMeterTreeNode node) {
        TestElement el = node.getTestElement();
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(id).append(" [").append(el.getClass().getSimpleName())
          .append("] \"").append(node.getName()).append("\"\n");
        for (PropertyIterator it = el.propertyIterator(); it.hasNext(); ) {
            JMeterProperty p = it.next();
            String v = p.getStringValue();
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            if (v.length() > 200) {
                v = v.substring(0, 200) + "…";
            }
            sb.append("    ").append(p.getName()).append(" = ").append(v).append("\n");
        }
        return sb.toString();
    }
}
