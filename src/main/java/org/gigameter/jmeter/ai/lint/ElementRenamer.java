package org.gigameter.jmeter.ai.lint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames JMeter test plan elements using AI suggestions based on
 * universal JMeter best-practice naming rules and optional user overrides.
 */
public class ElementRenamer {
    private static final Logger log = LoggerFactory.getLogger(ElementRenamer.class);

    private static final String DISABLED_PREFIX = "Disabled_";
    private static final int BATCH_SIZE = 20;

    private final AiService aiService;

    // Undo/redo state (static — survives across handler instances within a session)
    private static List<NameBackup> lastRenameOperation = new ArrayList<>();
    private static List<NameBackup> lastUndoneOperation = new ArrayList<>();

    public ElementRenamer(AiService aiService) {
        this.aiService = aiService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public String renameElements() {
        return renameElements(null);
    }

    /**
     * Renames elements in the currently selected scope.
     *
     * @param userCommand optional user instruction (naming style override), may be null
     */
    public String renameElements(String userCommand) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) return "Error: JMeter GUI is not available";

        JMeterTreeNode[] selectedNodes = guiPackage.getTreeListener().getSelectedNodes();
        if (selectedNodes == null || selectedNodes.length == 0) {
            return "Please select at least one element in the test plan";
        }

        // Build the combined SerializedPlan from selected scope
        SerializedPlan plan = buildPlanForScope(guiPackage, selectedNodes);

        if (plan.size() == 0) return "No elements found to rename";
        log.info("Lint scope: {} elements to rename", plan.size());

        // Ask AI in batches, collect id→newName mapping
        Map<Integer, String> renames = collectRenamesFromAi(plan, userCommand);

        if (renames.isEmpty()) {
            return "AI ответил, но не удалось распарсить переименования. Попробуйте ещё раз.";
        }

        // Apply renames
        int applied = applyRenames(guiPackage, plan.nodeById, renames);

        if (applied == 0) {
            return "AI ответил, но не удалось применить переименования. Попробуйте ещё раз.";
        }
        return "Переименовано: " + applied + " из " + plan.size() + " элементов.";
    }

    // =========================================================================
    // Scope resolution
    // =========================================================================

    private SerializedPlan buildPlanForScope(GuiPackage guiPackage, JMeterTreeNode[] selectedNodes) {
        boolean isRootSelected = false;
        for (JMeterTreeNode node : selectedNodes) {
            if ("Test Plan".equals(node.getName())) {
                isRootSelected = true;
                break;
            }
        }

        if (isRootSelected) {
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            return serializeSkippingRoot(root);
        }

        // Merge results from each selected node
        return mergeSerializedPlans(selectedNodes);
    }

    /**
     * Serializes from root but skips the root "Test Plan" node itself
     * (it's a container, not worth renaming).
     */
    private SerializedPlan serializeSkippingRoot(JMeterTreeNode root) {
        SerializedPlan full = JMeterPlanSerializer.serialize(root, 300, 20);
        // Drop element with id=1 (the Test Plan root) if it's "Test Plan"
        if (!full.elements.isEmpty() && "Test Plan".equals(full.elements.get(0).name)) {
            List<ElementEntry> trimmed = full.elements.subList(1, full.elements.size());
            // Rebuild nodeById without root
            Map<Integer, JMeterTreeNode> nodeById = new HashMap<>(full.nodeById);
            nodeById.remove(full.elements.get(0).id);
            return new SerializedPlan(trimmed, nodeById, full.truncated);
        }
        return full;
    }

    /**
     * Merges serializations of multiple selected nodes, re-assigning IDs globally.
     */
    private SerializedPlan mergeSerializedPlans(JMeterTreeNode[] selectedNodes) {
        List<ElementEntry> allEntries = new ArrayList<>();
        Map<Integer, JMeterTreeNode> allNodeById = new HashMap<>();

        int idCounter = 1;
        for (JMeterTreeNode node : selectedNodes) {
            boolean withChildren = node.getTestElement() != null
                    && node.getTestElement().getClass().getSimpleName().contains("ThreadGroup");

            SerializedPlan sub = withChildren
                    ? JMeterPlanSerializer.serialize(node, 300, 20)
                    : JMeterPlanSerializer.serialize(node, 1, 0);

            for (ElementEntry e : sub.elements) {
                JMeterTreeNode mappedNode = sub.nodeById.get(e.id);
                if (mappedNode == null) continue;
                ElementEntry renumbered = new ElementEntry(
                        idCounter, e.depth, e.type, e.name, e.props);
                allEntries.add(renumbered);
                allNodeById.put(idCounter, mappedNode);
                idCounter++;
            }
        }
        return new SerializedPlan(allEntries, allNodeById, false);
    }

    // =========================================================================
    // AI interaction
    // =========================================================================

    private Map<Integer, String> collectRenamesFromAi(SerializedPlan plan, String userCommand) {
        Map<Integer, String> result = new HashMap<>();
        int total = plan.size();

        for (int batchStart = 0; batchStart < total; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, total);
            List<ElementEntry> batch = plan.elements.subList(batchStart, batchEnd);

            // Build a mini-JSON for just this batch
            String batchJson = plan.toJson(batchStart, batchEnd);
            String prompt = LintRules.buildPrompt(batchJson, userCommand, batch.size());

            String response;
            try {
                response = aiService.generateResponse(Collections.singletonList(prompt));
            } catch (Exception e) {
                log.error("AI rename request failed for batch {}-{}", batchStart, batchEnd, e);
                continue;
            }

            JsonNode renamesNode = parseRenamesJson(response);
            if (renamesNode == null) {
                log.warn("Could not parse AI rename response for batch {}-{}: {}",
                        batchStart, batchEnd, response);
                continue;
            }

            for (JsonNode entry : renamesNode) {
                int id = entry.path("id").asInt(-1);
                String name = entry.path("name").asText("").trim();
                if (id >= 1 && !name.isEmpty()) {
                    result.put(id, name);
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Apply renames to GUI tree
    // =========================================================================

    private int applyRenames(GuiPackage guiPackage,
                             Map<Integer, JMeterTreeNode> nodeById,
                             Map<Integer, String> renames) {
        lastRenameOperation.clear();

        JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
        JMeterTreeNode[] selectedNodes = guiPackage.getTreeListener().getSelectedNodes();
        Set<JMeterTreeNode> selectedSet = new HashSet<>();
        if (selectedNodes != null) Collections.addAll(selectedSet, selectedNodes);

        int count = 0;
        for (Map.Entry<Integer, String> entry : renames.entrySet()) {
            int id = entry.getKey();
            String newName = entry.getValue();

            JMeterTreeNode node = nodeById.get(id);
            if (node == null) {
                log.warn("No node for id={}", id);
                continue;
            }

            TestElement element = node.getTestElement();
            if (element == null) continue;

            // Strip AI-added Disabled_ prefix; re-add it only if element is actually disabled
            if (newName.startsWith(DISABLED_PREFIX)) {
                newName = newName.substring(DISABLED_PREFIX.length());
            }
            if (!element.isEnabled()) {
                newName = DISABLED_PREFIX + newName;
            }

            lastRenameOperation.add(new NameBackup(node, element, node.getName()));
            node.setName(newName);
            element.setName(newName);
            element.setProperty(TestElement.NAME, newName);

            guiPackage.getTreeModel().nodeChanged(node);

            if (node.equals(currentNode)) {
                JMeterGUIComponent comp = guiPackage.getCurrentGui();
                if (comp != null) comp.configure(element);
            }
            if (selectedSet.contains(node)) {
                guiPackage.updateCurrentNode();
            }

            count++;
        }

        if (count > 0) {
            JMeterGUIComponent comp = guiPackage.getCurrentGui();
            if (comp != null && currentNode != null) comp.configure(currentNode.getTestElement());
            guiPackage.updateCurrentNode();
            guiPackage.getMainFrame().repaint();
        }
        return count;
    }

    // =========================================================================
    // Undo / Redo
    // =========================================================================

    public static String undoLastRename() {
        if (lastRenameOperation.isEmpty()) return "Nothing to undo.";

        lastUndoneOperation.clear();
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage != null
                ? guiPackage.getTreeListener().getCurrentNode() : null;

        for (NameBackup backup : lastRenameOperation) {
            lastUndoneOperation.add(new NameBackup(backup.node, backup.element, backup.node.getName()));
            backup.node.setName(backup.originalName);
            backup.element.setName(backup.originalName);
            backup.element.setProperty(TestElement.NAME, backup.originalName);
            if (guiPackage != null) {
                guiPackage.getTreeModel().nodeChanged(backup.node);
                if (backup.node.equals(currentNode)) {
                    JMeterGUIComponent comp = guiPackage.getCurrentGui();
                    if (comp != null) comp.configure(backup.element);
                }
            }
        }
        lastRenameOperation = new ArrayList<>();

        if (guiPackage != null) {
            guiPackage.updateCurrentNode();
            guiPackage.getMainFrame().repaint();
        }
        return "Restored " + lastUndoneOperation.size() + " element names.";
    }

    public static String redoLastUndo() {
        if (lastUndoneOperation.isEmpty()) return "Nothing to redo.";

        lastRenameOperation.clear();
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage != null
                ? guiPackage.getTreeListener().getCurrentNode() : null;

        for (NameBackup backup : lastUndoneOperation) {
            String currentName = backup.node.getName();
            lastRenameOperation.add(new NameBackup(backup.node, backup.element, currentName));
            backup.node.setName(backup.originalName);
            backup.element.setName(backup.originalName);
            backup.element.setProperty(TestElement.NAME, backup.originalName);
            if (guiPackage != null) {
                guiPackage.getTreeModel().nodeChanged(backup.node);
                if (backup.node.equals(currentNode)) {
                    JMeterGUIComponent comp = guiPackage.getCurrentGui();
                    if (comp != null) comp.configure(backup.element);
                }
            }
        }
        lastUndoneOperation = new ArrayList<>();

        if (guiPackage != null) {
            guiPackage.updateCurrentNode();
            guiPackage.getMainFrame().repaint();
        }
        return "Redone " + lastRenameOperation.size() + " renames.";
    }

    // =========================================================================
    // JSON parsing
    // =========================================================================

    private JsonNode parseRenamesJson(String response) {
        if (response == null || response.trim().isEmpty()) return null;
        try {
            String normalized = response.trim();
            if (normalized.startsWith("```")) {
                normalized = normalized.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
            }
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            JsonNode root = new ObjectMapper().readTree(normalized.substring(start, end + 1));
            JsonNode renames = root.path("renames");
            return renames.isArray() ? renames : null;
        } catch (Exception e) {
            log.error("JSON parse error in AI rename response", e);
            return null;
        }
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    private static class NameBackup {
        final JMeterTreeNode node;
        final TestElement element;
        final String originalName;

        NameBackup(JMeterTreeNode node, TestElement element, String originalName) {
            this.node = node;
            this.element = element;
            this.originalName = originalName;
        }
    }
}
