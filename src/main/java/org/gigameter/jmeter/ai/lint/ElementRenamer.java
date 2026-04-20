package org.gigameter.jmeter.ai.lint;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.gigameter.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
                    // First add the Thread Group itself
                    ElementInfo info = new ElementInfo();
                    info.node = node;
                    info.element = node.getTestElement();
                    info.name = node.getName();
                    info.type = node.getTestElement().getClass().getSimpleName();
                    info.isDisabled = !node.getTestElement().isEnabled();
                    info.properties = extractRelevantProperties(node.getTestElement());
                    elementsToRename.add(info);
                    log.info("Added Thread Group to rename: " + node.getName());
                    
                    // Then collect all its children
                    Enumeration<?> children = node.children();
                    while (children.hasMoreElements()) {
                        JMeterTreeNode childNode = (JMeterTreeNode) children.nextElement();
                        collectElementsToRename(childNode, elementsToRename, true);
                    }
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
    
    /**
     * Recursively collects elements to be renamed from the test plan.
     * 
     * @param node The current node to process
     * @param elementsToRename The list to populate with elements to rename
     * @param processChildren Whether to process child nodes recursively
     */
    private void collectElementsToRename(JMeterTreeNode node, List<ElementInfo> elementsToRename, boolean processChildren) {
        TestElement element = node.getTestElement();
        String nodeName = node.getName();
        String elementType = element.getClass().getSimpleName();
        
        // Always include the node if it's not the Test Plan
        // (we want to include Thread Groups and all other elements)
        if (!nodeName.equals("Test Plan")) {
            ElementInfo info = new ElementInfo();
            info.node = node;
            info.element = element;
            info.name = nodeName;
            info.type = elementType;
            info.isDisabled = !element.isEnabled();
            info.properties = extractRelevantProperties(element);
            
            elementsToRename.add(info);
            log.info("Added element to rename: " + nodeName + " (Type: " + elementType + ")");
        }
        
        // Process child nodes only if requested
        if (processChildren) {
            Enumeration<?> children = node.children();
            while (children.hasMoreElements()) {
                JMeterTreeNode childNode = (JMeterTreeNode) children.nextElement();
                collectElementsToRename(childNode, elementsToRename, true);
            }
        }
    }
    
    /**
     * Extracts relevant properties from a test element for AI analysis.
     * 
     * @param element The test element to extract properties from
     * @return A string containing relevant property information
     */
    private String extractRelevantProperties(TestElement element) {
        StringBuilder properties = new StringBuilder();
        
        PropertyIterator iter = element.propertyIterator();
        while (iter.hasNext()) {
            JMeterProperty prop = iter.next();
            String name = prop.getName();
            String value = prop.getStringValue();
            
            // Filter out properties that are likely to be useful for naming
            if (isRelevantProperty(name, value)) {
                properties.append(name).append(": ").append(value).append("\n");
            }
        }
        
        return properties.toString();
    }
    
    /**
     * Determines if a property is relevant for naming purposes.
     * 
     * @param name The property name
     * @param value The property value
     * @return True if the property is relevant, false otherwise
     */
    private boolean isRelevantProperty(String name, String value) {
        // Skip empty values
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        
        // Skip technical properties that don't provide semantic meaning
        String lowerName = name.toLowerCase();
        if (lowerName.contains("enabled") || 
            lowerName.contains("guiclass") || 
            lowerName.contains("testclass") || 
            lowerName.contains("testname") ||
            lowerName.contains("testplan") ||
            lowerName.equals("name")) {
            return false;
        }
        
        // Include properties that are likely to be useful for naming
        return lowerName.contains("path") ||
               lowerName.contains("url") ||
               lowerName.contains("domain") ||
               lowerName.contains("port") ||
               lowerName.contains("method") ||
               lowerName.contains("query") ||
               lowerName.contains("resource") ||
               lowerName.contains("endpoint") ||
               lowerName.contains("script") ||
               lowerName.contains("command") ||
               lowerName.contains("statement") ||
               lowerName.contains("expression") ||
               lowerName.contains("pattern") ||
               lowerName.contains("assertion") ||
               lowerName.contains("variable");
    }
    
    /**
     * Gets AI suggestions for renaming elements.
     * 
     * @param elementsToRename The list of elements to rename
     * @param command The user's command for how to rename elements
     * @return AI-generated suggestions for renaming
     */
    private String getAiSuggestions(List<ElementInfo> elementsToRename, String command) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Help the user to rename the elements in the test plan. Here are the elements details. Give me meaningful names for the elements.\n");
        prompt.append("e.g. HTTP_PostLogin, HTTP_Request_To_Authenticate_Users_And_Get_Token, TG_GenerateOrders\n");
        
        // Add user's specific naming style instructions if provided
        if (command != null && !command.equalsIgnoreCase("rename")) {
            prompt.append("User has requested the following naming style: " + command + "\n");
            
            // Check if the user has specific instructions about numbering or beginning format
            boolean hasNumberingInstructions = command.toLowerCase().contains("number") || 
                                             command.toLowerCase().contains("sequenc") || 
                                             command.toLowerCase().contains("order");
                                             
            boolean hasBeginningInstructions = command.toLowerCase().contains("beginning") || 
                                             command.toLowerCase().contains("start") || 
                                             command.toLowerCase().contains("prefix");
            
            // Check for the specific combined case of camel case with numbers at the beginning
            boolean isCamelCase = command.toLowerCase().contains("camel case") || command.toLowerCase().contains("camelcase");
            boolean isNumbersAtBeginning = command.toLowerCase().contains("add the numbers in the beginning") || 
                                         command.toLowerCase().contains("numbers in the beginning") || 
                                         command.toLowerCase().contains("numbers at the beginning");
            
            if (isCamelCase && isNumbersAtBeginning) {
                prompt.append("\nIMPORTANT: The user wants camelCase with numbers at the BEGINNING of each name. " +
                              "For example: '10httpLogin', '20getUsers', '30verifyOrder'. " +
                              "Do NOT use underscores or any other format. The first part should be the number, " +
                              "followed immediately by the camelCase name (first word lowercase, subsequent words capitalized).\n");
            }
            // Special case for "add the numbers in the beginning" only
            else if (isNumbersAtBeginning) {
                prompt.append("\nIMPORTANT: The user wants numbers at the BEGINNING of each name. For example, instead of 'httpLogin' use '10HttpLogin', '20HttpGetUsers', etc.\n");
            } 
            // Only add the default sequencing suggestion if the user hasn't specified numbering or beginning preferences
            else if (!hasNumberingInstructions && !hasBeginningInstructions) {
                prompt.append("\nIf possible, sequentially rename the elements in the test plan. e.g. HTTP_10_Login, HTTP_20_Request_To_Authenticate_Users_And_Get_Token, TG_10_GenerateOrders, TG_20_Verify_Orders\n");
            }
            
            // Special case for camel case (only if not already handled in the combined case)
            if (isCamelCase && !isNumbersAtBeginning) {
                prompt.append("\nFor camelCase, the first letter should be lowercase, and the first letter of each subsequent word should be uppercase. For example: 'httpRequest', 'getUsers', 'verifyLogin'.\n");
            }
        } else {
            prompt.append("Use the snake_case by default, unless the user specifies otherwise.\n");
            prompt.append("\nIf possible, sequentially rename the elements in the test plan. e.g. HTTP_10_Login, HTTP_20_Request_To_Authenticate_Users_And_Get_Token, TG_10_GenerateOrders, TG_20_Verify_Orders\n");
        }
        
        prompt.append("\n");
        prompt.append("Elements to rename:\n\n");
        
        AtomicInteger counter = new AtomicInteger(1);
        elementsToRename.forEach(info -> {
            prompt.append("Element ").append(counter.getAndIncrement()).append(":\n");
            prompt.append("Type: ").append(info.type).append("\n");
            prompt.append("Current Name: ").append(info.name).append("\n");
            prompt.append("Is Disabled: ").append(info.isDisabled).append("\n");
            if (!info.properties.isEmpty()) {
                prompt.append("Properties:\n").append(info.properties).append("\n");
            }
            prompt.append("\n");
        });
        
        prompt.append("Respond with ONLY valid JSON, no explanations. Format:\n");
        prompt.append("{\"renames\":[{\"index\":1,\"name\":\"NewName\"},{\"index\":2,\"name\":\"NewName2\"}]}\n");
        prompt.append("Rules: names must be meaningful and reflect the element's purpose. Do NOT add 'Disabled_' prefix.\n");
        
        try {
            return aiService.generateResponse(java.util.Collections.singletonList(prompt.toString()));
        } catch (Exception e) {
            log.error("Error getting AI suggestions", e);
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

