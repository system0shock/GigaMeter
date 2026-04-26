package org.gigameter.jmeter.ai.lint;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A utility class for renaming JMeter test plan elements using AI suggestions.
 * This class analyzes test plan elements and suggests meaningful names based on their properties.
 */
public class ElementRenamer {
    private static final Logger log = LoggerFactory.getLogger(ElementRenamer.class);
    private final AiService aiService;
    private static final String DISABLED_PREFIX = "Disabled_";
    
    // Store the original names for undo functionality
    private static List<NameBackup> lastRenameOperation = new ArrayList<>();
    
    // Store the undone operations for redo functionality
    private static List<NameBackup> lastUndoneOperation = new ArrayList<>();
    
    /**
     * Constructor for ElementRenamer.
     * 
     * @param aiService The AI service to use for generating name suggestions
     */
    public ElementRenamer(AiService aiService) {
        this.aiService = aiService;
    }
    
    /**
     * Renames elements in the test plan based on the current selection.
     * If the root test plan is selected, all elements are renamed.
     * If specific elements are selected, only those elements are renamed.
     * 
     * @return A message indicating the result of the renaming operation
     */
    public String renameElements() {
        // Call the overloaded method with null command for backward compatibility
        return renameElements(null);
    }
    
    /**
     * Renames elements in the test plan based on the current selection.
     * If the root test plan is selected, all elements are renamed.
     * If specific elements are selected, only those elements are renamed.
     * 
     * @param command The user's command for how to rename elements (e.g., "make it all caps", "use camelcase")
     * @return A message indicating the result of the renaming operation
     */
    public String renameElements(String command) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null");
            return "Error: JMeter GUI is not available";
        }
        
        JMeterTreeNode[] selectedNodes = guiPackage.getTreeListener().getSelectedNodes();
        if (selectedNodes == null || selectedNodes.length == 0) {
            log.error("No elements selected");
            return "Please select at least one element in the test plan";
        }
        
        // Check if the root test plan is selected
        boolean isRootSelected = false;
        boolean isThreadGroupSelected = false;
        for (JMeterTreeNode node : selectedNodes) {
            if (node.getName().equals("Test Plan")) {
                isRootSelected = true;
                break;
            }
            // Check if any Thread Group is selected
            if (node.getTestElement().getClass().getSimpleName().contains("ThreadGroup")) {
                isThreadGroupSelected = true;
            }
        }
        
        List<ElementInfo> elementsToRename = new ArrayList<>();
        
        if (isRootSelected) {
            // Rename all elements in the test plan
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            collectElementsToRename(root, elementsToRename, true);
        } else if (isThreadGroupSelected) {
            // For Thread Groups, include the Thread Group itself and all its children
            for (JMeterTreeNode node : selectedNodes) {
                if (node.getTestElement().getClass().getSimpleName().contains("ThreadGroup")) {
                    // For Thread Groups, collect the Thread Group itself and all its children
                    log.info("Processing Thread Group: " + node.getName());
                    // Collect Thread Group and all its children iteratively
                    collectElementsToRename(node, elementsToRename, true);
                } else {
                    // For non-Thread Group elements, just collect the element itself
                    collectElementsToRename(node, elementsToRename, false);
                }
            }
        } else {
            // Rename only selected elements without their children
            for (JMeterTreeNode node : selectedNodes) {
                collectElementsToRename(node, elementsToRename, false);
            }
        }
        
        if (elementsToRename.isEmpty()) {
            return "No elements found to rename";
        }
        
        log.info("Collected " + elementsToRename.size() + " elements to rename");
        
        // Generate AI suggestions for renaming
        String suggestions = getAiSuggestions(elementsToRename, command);
        if (suggestions == null || suggestions.isEmpty()) {
            return "Failed to get AI suggestions for renaming";
        }
        
        // Apply the suggestions to rename the elements
        int renamedCount = applyRenameSuggestions(elementsToRename, suggestions);

        if (renamedCount == 0) {
            return "AI ответил, но не удалось применить переименования. " +
                    "Попробуйте ещё раз или упростите сценарий.";
        }
        return "Переименовано элементов: " + renamedCount + " из " + elementsToRename.size() + ".";
    }
    
    private static final String[] LINT_PROP_KEYS = {
        "HTTPSampler.method", "HTTPSampler.path", "HTTPSampler.domain",
        "scriptLanguage", "script"
    };

    private void collectElementsToRename(JMeterTreeNode startNode, List<ElementInfo> result, boolean processChildren) {
        // Iterative DFS to avoid stack overflow on deep plans
        Deque<JMeterTreeNode> stack = new ArrayDeque<>();
        stack.push(startNode);

        while (!stack.isEmpty()) {
            JMeterTreeNode node = stack.pop();
            TestElement element = node.getTestElement();
            if (element == null) continue;

            String nodeName = node.getName();
            if (!"Test Plan".equals(nodeName)) {
                ElementInfo info = new ElementInfo();
                info.node = node;
                info.element = element;
                info.name = nodeName;
                info.type = element.getClass().getSimpleName();
                info.isDisabled = !element.isEnabled();
                info.properties = extractKeyProps(element);
                result.add(info);
                log.debug("Collected for lint: {} ({})", nodeName, info.type);
            }

            if (processChildren || node == startNode) {
                // Push children in reverse order so first child is processed first
                Enumeration<?> children = node.children();
                List<JMeterTreeNode> childList = new ArrayList<>();
                while (children.hasMoreElements()) {
                    Object c = children.nextElement();
                    if (c instanceof JMeterTreeNode) childList.add((JMeterTreeNode) c);
                }
                for (int i = childList.size() - 1; i >= 0; i--) {
                    stack.push(childList.get(i));
                }
                // After the start node, always recurse if processChildren was true
                if (!processChildren) break; // only the root node, no children
            }
        }
    }

    private String extractKeyProps(TestElement element) {
        StringBuilder sb = new StringBuilder();
        for (String key : LINT_PROP_KEYS) {
            try {
                String val = element.getPropertyAsString(key);
                if (val != null && !val.trim().isEmpty()) {
                    if (val.length() > 80) val = val.substring(0, 80) + "...";
                    sb.append(key).append(": ").append(val).append("\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }
    
    private static final int BATCH_SIZE = 20;

    /**
     * Gets AI rename suggestions for all elements, using batched requests if needed.
     * Returns combined JSON string of all renames with globally consistent indices.
     */
    private String getAiSuggestions(List<ElementInfo> elementsToRename, String command) {
        if (elementsToRename.isEmpty()) return null;

        if (elementsToRename.size() <= BATCH_SIZE) {
            return askAiForBatch(elementsToRename, 0, command);
        }

        // Batch mode: collect all partial responses and merge into one JSON
        StringBuilder merged = new StringBuilder("{\"renames\":[");
        boolean firstEntry = true;
        int batchStart = 0;
        while (batchStart < elementsToRename.size()) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, elementsToRename.size());
            List<ElementInfo> batch = elementsToRename.subList(batchStart, batchEnd);
            String batchResponse = askAiForBatch(batch, batchStart, command);
            if (batchResponse == null) {
                batchStart = batchEnd;
                continue;
            }
            JsonNode renamesNode = parseRenamesJson(batchResponse);
            if (renamesNode != null) {
                for (JsonNode entry : renamesNode) {
                    if (!firstEntry) merged.append(",");
                    firstEntry = false;
                    merged.append(entry.toString());
                }
            }
            batchStart = batchEnd;
        }
        merged.append("]}");
        return merged.toString();
    }

    private String askAiForBatch(List<ElementInfo> batch, int indexOffset, String command) {
        int total = batch.size();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Переименуй ВСЕ ").append(total).append(" элементов JMeter тест-плана.\n");
        prompt.append("Ты ОБЯЗАН вернуть ровно ").append(total)
              .append(" записей в массиве renames — по одной на каждый элемент, без пропусков.\n\n");

        if (command != null && !command.equalsIgnoreCase("rename")) {
            prompt.append("Стиль именования: ").append(command).append("\n");
        } else {
            prompt.append("Стиль именования: snake_case с порядковым префиксом.\n");
            prompt.append("Пример: HTTP_10_Login, HTTP_20_GetOrders, TG_10_Checkout\n");
        }

        prompt.append("\nЭлементы:\n");
        for (int i = 0; i < batch.size(); i++) {
            ElementInfo info = batch.get(i);
            int globalIndex = indexOffset + i + 1;
            prompt.append("index=").append(globalIndex)
                  .append(" type=").append(info.type)
                  .append(" name=\"").append(info.name).append("\"");
            if (!info.properties.isEmpty()) {
                // Include only the first line of properties to keep prompt tight
                String firstProp = info.properties.split("\n")[0];
                prompt.append(" props=[").append(firstProp).append("]");
            }
            prompt.append("\n");
        }

        prompt.append("\nОтвет — только JSON без объяснений:\n");
        prompt.append("{\"renames\":[{\"index\":1,\"name\":\"NewName\"},...,{\"index\":")
              .append(indexOffset + total).append(",\"name\":\"NewNameN\"}]}\n");
        prompt.append("Имена: осмысленные, отражающие назначение элемента. Без префикса 'Disabled_'.\n");

        try {
            return aiService.generateResponse(java.util.Collections.singletonList(prompt.toString()));
        } catch (Exception e) {
            log.error("Error getting AI rename suggestions for batch starting at {}", indexOffset, e);
            return null;
        }
    }
    
    /**
     * Applies the AI-suggested names to the elements.
     * 
     * @param elementsToRename The list of elements to rename
     * @param suggestions The AI-generated suggestions
     * @return The number of elements successfully renamed
     */
    private int applyRenameSuggestions(List<ElementInfo> elementsToRename, String suggestions) {
        int renamedCount = 0;
        
        // Clear previous rename operation backup
        lastRenameOperation.clear();
        
        // Parse JSON response from AI
        JsonNode renamesNode = parseRenamesJson(suggestions);
        if (renamesNode == null) {
            log.error("Failed to parse AI response as JSON. Response was: {}", suggestions);
            return 0;
        }

        log.info("Parsed {} rename entries from AI response", renamesNode.size());

        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage != null ? guiPackage.getTreeListener().getCurrentNode() : null;
        JMeterTreeNode[] selectedNodes = guiPackage != null ? guiPackage.getTreeListener().getSelectedNodes() : null;

        Set<JMeterTreeNode> selectedNodesSet = new HashSet<>();
        if (selectedNodes != null) {
            for (JMeterTreeNode node : selectedNodes) {
                selectedNodesSet.add(node);
            }
        }

        for (JsonNode entry : renamesNode) {
            int index = entry.path("index").asInt(-1);
            String newName = entry.path("name").asText("").trim();

            if (index < 1 || index > elementsToRename.size() || newName.isEmpty()) {
                log.warn("Skipping invalid rename entry: {}", entry);
                continue;
            }

            ElementInfo info = elementsToRename.get(index - 1);

            if (newName.startsWith(DISABLED_PREFIX)) {
                newName = newName.substring(DISABLED_PREFIX.length());
            }
            if (info.isDisabled) {
                newName = DISABLED_PREFIX + newName;
            }

            log.info("Renaming element {}: {} -> {}", index, info.name, newName);
            lastRenameOperation.add(new NameBackup(info.node, info.element, info.name));

            info.node.setName(newName);
            info.element.setName(newName);
            info.element.setProperty(TestElement.NAME, newName);

            if (guiPackage != null) {
                if (currentNode != null && currentNode.equals(info.node)) {
                    JMeterGUIComponent comp = guiPackage.getCurrentGui();
                    if (comp != null) {
                        comp.configure(info.element);
                    }
                }
                guiPackage.getTreeModel().nodeChanged(info.node);
                if (selectedNodesSet.contains(info.node)) {
                    guiPackage.updateCurrentNode();
                }
            }

            renamedCount++;
        }

        if (guiPackage != null && renamedCount > 0) {
            JMeterGUIComponent comp = guiPackage.getCurrentGui();
            if (comp != null && currentNode != null) {
                comp.configure(currentNode.getTestElement());
            }
            guiPackage.updateCurrentNode();
            guiPackage.getMainFrame().repaint();
        }
        
        return renamedCount;
    }

    private JsonNode parseRenamesJson(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = response.trim();
            if (normalized.startsWith("```")) {
                normalized = normalized.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
            }
            int start = normalized.indexOf('{');
            int end = normalized.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(normalized.substring(start, end + 1));
            JsonNode renames = root.path("renames");
            return renames.isArray() ? renames : null;
        } catch (Exception e) {
            log.error("JSON parse error in AI rename response", e);
            return null;
        }
    }

    /**
     * Undoes the last rename operation.
     * 
     * @return A message indicating the result of the undo operation
     */
    public static String undoLastRename() {
        if (lastRenameOperation.isEmpty()) {
            return "Nothing to undo.";
        }
        
        int restoredCount = 0;
        
        // Clear previous undone operation backup before storing new ones
        lastUndoneOperation.clear();
        
        // Get the currently selected nodes for special handling
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage != null ? guiPackage.getTreeListener().getCurrentNode() : null;
        JMeterTreeNode[] selectedNodes = guiPackage != null ? guiPackage.getTreeListener().getSelectedNodes() : null;
        
        // Create a set of selected nodes for faster lookup
        Set<JMeterTreeNode> selectedNodesSet = new HashSet<>();
        if (selectedNodes != null) {
            for (JMeterTreeNode node : selectedNodes) {
                selectedNodesSet.add(node);
            }
        }
        
        for (NameBackup backup : lastRenameOperation) {
            // Store the current name before undoing for redo functionality
            lastUndoneOperation.add(new NameBackup(backup.node, backup.element, backup.node.getName()));
            
            backup.node.setName(backup.originalName);
            backup.element.setName(backup.originalName);
            backup.element.setProperty(TestElement.NAME, backup.originalName);
            
            // Check if this is the currently selected node
            boolean isCurrentNode = (currentNode != null && currentNode.equals(backup.node));
            boolean isSelectedNode = selectedNodesSet.contains(backup.node);
            
            // Update the GUI component if this is the currently selected node
            if (isCurrentNode && guiPackage != null) {
                // Get the current GUI component and update it
                JMeterGUIComponent comp = guiPackage.getCurrentGui();
                if (comp != null) {
                    comp.configure(backup.element);
                    log.info("Configured current GUI component for element: " + backup.originalName);
                }
            }
            
            // For all nodes (including selected ones), ensure the tree model is updated
            if (guiPackage != null) {
                guiPackage.getTreeModel().nodeChanged(backup.node);
                log.info("Notified tree model of node change for element: " + backup.originalName);
                
                // For selected nodes, apply additional update to ensure visibility
                if (isSelectedNode) {
                    // Force a more thorough update for selected nodes
                    guiPackage.updateCurrentNode();
                    log.info("Updated current node for selected element: " + backup.originalName);
                }
            }
            
            restoredCount++;
        }
        
        // Clear the backup after undoing
        lastRenameOperation = new ArrayList<>();
        
        // After all undos are applied, force a final GUI refresh
        if (guiPackage != null) {
            // Update the current GUI component
            JMeterGUIComponent comp = guiPackage.getCurrentGui();
            if (comp != null && currentNode != null) {
                comp.configure(currentNode.getTestElement());
                log.info("Final GUI component update for current node");
            }
            
            // Ensure the tree is properly updated
            guiPackage.updateCurrentNode();
            log.info("Final update of current node");
            
            // Repaint the main frame to ensure all visual changes are applied
            guiPackage.getMainFrame().repaint();
            log.info("Final repaint of main frame");
        }
        
        return "Successfully restored " + restoredCount + " element names.";
    }
    
    /**
     * Redoes the last undone rename operation.
     * 
     * @return A message indicating the result of the redo operation
     */
    public static String redoLastUndo() {
        if (lastUndoneOperation.isEmpty()) {
            return "Nothing to redo.";
        }
        
        int redoneCount = 0;
        
        // Clear previous rename operation backup before storing new ones
        lastRenameOperation.clear();
        
        // Get the currently selected nodes for special handling
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = guiPackage != null ? guiPackage.getTreeListener().getCurrentNode() : null;
        JMeterTreeNode[] selectedNodes = guiPackage != null ? guiPackage.getTreeListener().getSelectedNodes() : null;
        
        // Create a set of selected nodes for faster lookup
        Set<JMeterTreeNode> selectedNodesSet = new HashSet<>();
        if (selectedNodes != null) {
            for (JMeterTreeNode node : selectedNodes) {
                selectedNodesSet.add(node);
            }
        }
        
        for (NameBackup backup : lastUndoneOperation) {
            // Store the current name before redoing for undo functionality
            String currentName = backup.node.getName();
            lastRenameOperation.add(new NameBackup(backup.node, backup.element, currentName));
            
            // Apply the new name (which is stored in originalName in the backup)
            String newName = backup.originalName;
            backup.node.setName(newName);
            backup.element.setName(newName);
            backup.element.setProperty(TestElement.NAME, newName);
            
            // Check if this is the currently selected node
            boolean isCurrentNode = (currentNode != null && currentNode.equals(backup.node));
            boolean isSelectedNode = selectedNodesSet.contains(backup.node);
            
            // Update the GUI component if this is the currently selected node
            if (isCurrentNode && guiPackage != null) {
                // Get the current GUI component and update it
                JMeterGUIComponent comp = guiPackage.getCurrentGui();
                if (comp != null) {
                    comp.configure(backup.element);
                    log.info("Configured current GUI component for element: " + newName);
                }
            }
            
            // For all nodes (including selected ones), ensure the tree model is updated
            if (guiPackage != null) {
                guiPackage.getTreeModel().nodeChanged(backup.node);
                log.info("Notified tree model of node change for element: " + newName);
                
                // For selected nodes, apply additional update to ensure visibility
                if (isSelectedNode) {
                    // Force a more thorough update for selected nodes
                    guiPackage.updateCurrentNode();
                    log.info("Updated current node for selected element: " + newName);
                }
            }
            
            redoneCount++;
        }
        
        // Clear the undone backup after redoing
        lastUndoneOperation = new ArrayList<>();
        
        // After all redos are applied, force a final GUI refresh
        if (guiPackage != null) {
            // Update the current GUI component
            JMeterGUIComponent comp = guiPackage.getCurrentGui();
            if (comp != null && currentNode != null) {
                comp.configure(currentNode.getTestElement());
                log.info("Final GUI component update for current node");
            }
            
            // Ensure the tree is properly updated
            guiPackage.updateCurrentNode();
            log.info("Final update of current node");
            
            // Repaint the main frame to ensure all visual changes are applied
            guiPackage.getMainFrame().repaint();
            log.info("Final repaint of main frame");
        }
        
        return "Successfully redone " + redoneCount + " element renames.";
    }
    
    /**
     * Inner class to hold information about elements to be renamed.
     */
    private static class ElementInfo {
        JMeterTreeNode node;
        TestElement element;
        String name;
        String type;
        boolean isDisabled;
        String properties;
    }
    
    /**
     * Inner class to store original element names for undo functionality.
     */
    private static class NameBackup {
        JMeterTreeNode node;
        TestElement element;
        String originalName;
        
        public NameBackup(JMeterTreeNode node, TestElement element, String originalName) {
            this.node = node;
            this.element = element;
            this.originalName = originalName;
        }
    }
}

