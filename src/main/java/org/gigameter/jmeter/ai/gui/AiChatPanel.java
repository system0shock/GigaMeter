package org.gigameter.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.gigameter.jmeter.ai.intellisense.InputBoxIntellisense;

import com.openai.models.Model;
import org.apache.jorphan.gui.JMeterUIDefaults;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.gigameter.jmeter.ai.service.DeepSeekService;
import org.gigameter.jmeter.ai.service.GigaChatService;
import org.gigameter.jmeter.ai.plan.PlanCommandHandler;
import org.gigameter.jmeter.ai.usage.UsageCommandHandler;
import org.gigameter.jmeter.ai.utils.AiConfig;
import org.gigameter.jmeter.ai.utils.JMeterElementManager;
import org.gigameter.jmeter.ai.utils.JMeterElementRequestHandler;
import org.gigameter.jmeter.ai.utils.Models;
import org.gigameter.jmeter.ai.utils.VersionUtils;
import org.gigameter.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.gigameter.jmeter.ai.lint.LintCommandHandler;
import org.gigameter.jmeter.ai.wrap.WrapCommandHandler;
import org.gigameter.jmeter.ai.wrap.WrapUndoRedoHandler;
import org.gigameter.jmeter.ai.service.OpenAiService;
import org.gigameter.jmeter.ai.service.AiService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * This class has been refactored to improve composability, readability, and
 * reusability
 * by delegating responsibilities to specialized component classes.
 */
public class AiChatPanel extends JPanel implements PropertyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    private JTextArea messageField;
    private JButton sendButton;
    private JComboBox<String> modelSelector;
    private List<String> conversationHistory;
    private DeepSeekService deepSeekService;
    private OpenAiService openAiService;
    private GigaChatService gigaChatService;
    private TreeNavigationButtons treeNavigationButtons;
    private JPanel navigationPanel; // Added field for navigation panel

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final MessageProcessor messageProcessor;
    private final ElementSuggestionManager elementSuggestionManager;

    // Track the last command type for undo/redo operations
    private enum LastCommandType {
        NONE,
        LINT,
        WRAP,
        PLAN_APPLY
    }

    private LastCommandType lastCommandType = LastCommandType.NONE;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        // Initialize services and utilities
        deepSeekService = new DeepSeekService();
        openAiService = new OpenAiService();
        gigaChatService = new GigaChatService();
        messageProcessor = new MessageProcessor();

        // Initialize tree navigation buttons with action listeners
        treeNavigationButtons = new TreeNavigationButtons();
        treeNavigationButtons.setUpButtonActionListener();
        treeNavigationButtons.setDownButtonActionListener();

        // Register for UI refresh events (for zoom functionality)
        UIManager.addPropertyChangeListener(this);

        conversationHistory = new ArrayList<>();

        // Set up the panel layout
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));
        setMinimumSize(new Dimension(350, 400));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // Initialize model selector with loading state
        modelSelector = new JComboBox<>();
        modelSelector.addItem(null); // Add empty item while loading
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                if (value == null) {
                    return super.getListCellRendererComponent(list, "Загрузка моделей...", index, isSelected,
                            cellHasFocus);
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        // Load models in background
        loadModelsInBackground();

        // Add a listener to log model changes
        modelSelector.addActionListener(e -> {
            String selectedModel = (String) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel);
                if (selectedModel.startsWith("openai:")) {
                    openAiService.setModel(selectedModel.substring(7));
                } else if (selectedModel.startsWith("giga:")) {
                    gigaChatService.setModel(selectedModel.substring(5));
                } else if (selectedModel.startsWith("deepseek:")) {
                    deepSeekService.setModel(selectedModel.substring(9));
                }
            }
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));

        // Create a header panel for the title and new chat button
        JPanel headerPanel = createHeaderPanel();
        chatPanel.add(headerPanel, BorderLayout.NORTH);

        // Initialize chat area
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        // Use the system default font with larger size
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);
        chatArea.setFont(largerFont);

        // Store the base font size for scaling
        baseChatFontSize = largerFont.getSize2D();

        // Set default paragraph attributes for left alignment
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        // Add keyboard shortcut for undo (Cmd+Z on Mac, Ctrl+Z on Windows/Linux)
        InputMap inputMap = chatArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = chatArea.getActionMap();

        // Define the key stroke based on the platform - using modern API instead of
        // deprecated Event.META_MASK
        KeyStroke undoKeyStroke;
        KeyStroke redoKeyStroke;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            // Mac (Cmd+Z for undo, Cmd+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else if (osName.contains("linux")) {
            // Linux (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else {
            // Windows (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        }

        inputMap.put(undoKeyStroke, "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Perform undo based on the last command type
                switch (lastCommandType) {
                    case WRAP:
                        undoLastWrap();
                        break;
                    case LINT:
                        undoLastRename();
                        break;
                    default:
                        // If no recent command, try to determine based on context
                        GuiPackage guiPackage = GuiPackage.getInstance();
                        if (guiPackage != null) {
                            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                            if (currentNode != null && currentNode.getTestElement() instanceof TransactionController) {
                                undoLastWrap();
                            } else {
                                undoLastRename();
                            }
                        }
                        break;
                }
            }
        });

        // Add keyboard shortcut for redo
        inputMap.put(redoKeyStroke, "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Perform redo based on the last command type
                switch (lastCommandType) {
                    case WRAP:
                        // Wrap operations don't support redo due to complexity of recreating
                        // Transaction Controllers
                        try {
                                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                    "Повтор (redo) для @wrap не поддерживается. При необходимости запустите @wrap снова.",
                                    Color.BLUE, false);
                        } catch (BadLocationException ex) {
                            log.error("Error displaying message", ex);
                        }
                        break;
                    case LINT:
                        redoLastUndo();
                        break;
                    default:
                        // If no recent command, try to determine based on context
                        GuiPackage guiPackage = GuiPackage.getInstance();
                        if (guiPackage != null) {
                            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                            if (currentNode != null && currentNode.getTestElement() instanceof TransactionController) {
                                // If a Transaction Controller is selected, inform user that redo is not
                                // supported
                                try {
                                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                            "Повтор (redo) для @wrap не поддерживается. При необходимости запустите @wrap снова.",
                                            Color.BLUE, false);
                                } catch (BadLocationException ex) {
                                    log.error("Error displaying message", ex);
                                }
                            } else {
                                redoLastUndo();
                            }
                        }
                        break;
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);

        // Add the chat panel to the center of the main panel
        add(chatPanel, BorderLayout.CENTER);

        // Create the bottom panel with model selector and input controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        // Add model selector to the bottom panel
        FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
        JPanel modelPanel = new JPanel(flowLayout);
        JLabel modelLabel = new JLabel("Модель: ");
        modelPanel.add(modelLabel);
        modelPanel.add(modelSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);

        // Create the navigation panel for tree navigation and element buttons
        navigationPanel = new JPanel();
        navigationPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("Подсказки элементов"));
        navigationPanel.setBackground(new Color(245, 245, 250)); // Light background to make it stand out

        // Add navigation buttons to the panel
        navigationPanel.add(treeNavigationButtons.getUpButton());
        navigationPanel.add(treeNavigationButtons.getDownButton());

        // Add a separator
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 30));
        navigationPanel.add(separator);

        // Set minimum height to ensure buttons are visible
        navigationPanel.setMinimumSize(new Dimension(100, 70));
        navigationPanel.setPreferredSize(new Dimension(500, 70));

        // Initialize element suggestion manager with the navigation panel
        elementSuggestionManager = new ElementSuggestionManager(navigationPanel);

        // Make sure the navigation panel is visible
        navigationPanel.setVisible(true);

        bottomPanel.add(navigationPanel, BorderLayout.CENTER);

        // Create the input panel with message field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));

        // Initialize message field
        messageField = new JTextArea(3, 20);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(largerFont);

        // Store the base font size for scaling
        baseMessageFontSize = largerFont.getSize2D();
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        // Setup intellisense for command suggestions
        new InputBoxIntellisense(messageField);

        // Add key listener for Enter to send message
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); // Prevent newline from being added
                    sendMessage();
                }
            }
        });

        // Add focus listener to store selected text when clicking in the chat box
        // messageField.addFocusListener(new FocusAdapter() {
        // @Override
        // public void focusGained(FocusEvent e) {
        // log.info("Message field gained focus, storing selected text");
        // CodeCommandHandler.storeSelectedText();
        // }
        // });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Initialize send button
        sendButton = new JButton("Отправить");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.SOUTH);

        // Add the bottom panel to the main panel
        add(bottomPanel, BorderLayout.SOUTH);

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Creates the header panel with title and new chat button.
     * 
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(new Color(240, 240, 240));

        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("GigaMeter v" + VersionUtils.getVersion());
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Create the "New Chat" button with a plus icon
        JButton newChatButton = new JButton("+");
        newChatButton.setToolTipText("Начать новый диалог");
        newChatButton.setFont(new Font(newChatButton.getFont().getName(), Font.BOLD, 16));
        newChatButton.setFocusPainted(false);
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        newChatButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());

        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);

        return headerPanel;
    }

    /**
     * Loads the available models in the background.
     */
    private void loadModelsInBackground() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                // Get models from configured services
                List<String> allModels = new ArrayList<>();

                // Add OpenAI models
                try {
                    com.openai.models.ModelListPage openAiModels = Models.getOpenAiModels(openAiService.getClient());
                    if (openAiModels != null && openAiModels.data() != null) {
                        // Convert OpenAI models to string IDs
                        for (Model openAiModel : openAiModels.data()) {
                            // Only include GPT models and filter out specific model types
                            if (openAiModel.id().startsWith("gpt") &&
                                    !openAiModel.id().contains("audio") &&
                                    !openAiModel.id().contains("tts") &&
                                    !openAiModel.id().contains("whisper") &&
                                    !openAiModel.id().contains("davinci") &&
                                    !openAiModel.id().contains("search") &&
                                    !openAiModel.id().contains("transcribe") &&
                                    !openAiModel.id().contains("realtime") &&
                                    !openAiModel.id().contains("instruct")) {

                                String modelId = "openai:" + openAiModel.id();
                                allModels.add(modelId);
                                log.debug("Added OpenAI model to selector: {}", openAiModel.id());
                            }
                        }
                        log.info("Added OpenAI models to selector");
                    }
                } catch (Exception e) {
                    log.error("Error adding OpenAI models: {}", e.getMessage(), e);
                }

                // Add DeepSeek models
                try {
                    List<String> deepSeekModels = deepSeekService.getModelIds();
                    for (String deepSeekModel : deepSeekModels) {
                        String modelId = "deepseek:" + deepSeekModel;
                        allModels.add(modelId);
                        log.debug("Added DeepSeek model to selector: {}", modelId);
                    }
                    log.info("Added {} DeepSeek models to selector", deepSeekModels.size());
                } catch (Exception e) {
                    log.error("Error adding DeepSeek models: {}", e.getMessage(), e);
                }

                // Add GigaChat models
                try {
                    List<String> gigaModels = gigaChatService.getModelIds();
                    for (String gigaModel : gigaModels) {
                        String modelId = "giga:" + gigaModel;
                        allModels.add(modelId);
                        log.debug("Added GigaChat model to selector: {}", modelId);
                    }
                    log.info("Added {} GigaChat models to selector", gigaModels.size());
                } catch (Exception e) {
                    log.error("Error adding GigaChat models: {}", e.getMessage(), e);
                }

                return allModels;
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    // Get the default model ID from configured provider
                    String defaultService = AiConfig.getProperty("jmeter.ai.service.type", "openai");
                    String defaultModelId;
                    if ("openai".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "openai:" + openAiService.getCurrentModel();
                    } else if ("deepseek".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "deepseek:" + deepSeekService.getCurrentModel();
                    } else if ("giga".equalsIgnoreCase(defaultService)
                            || "gigachat".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "giga:" + gigaChatService.getCurrentModel();
                    } else {
                        defaultModelId = "openai:" + openAiService.getCurrentModel();
                    }
                    log.info("Default model ID: {}", defaultModelId);

                    String defaultModel = null;

                    for (String model : models) {
                        modelSelector.addItem(model);
                        if (model.equals(defaultModelId)) {
                            defaultModel = model;
                        }
                    }

                    // Select the default model if found
                    if (defaultModel != null) {
                        modelSelector.setSelectedItem(defaultModel);
                        log.info("Selected default model: {}", defaultModel);
                    } else if (modelSelector.getItemCount() > 0) {
                        // If default model not found, select the first one
                        modelSelector.setSelectedIndex(0);
                        String selectedModel = (String) modelSelector.getSelectedItem();
                        if (selectedModel != null) {
                            if (selectedModel.startsWith("openai:")) {
                                openAiService.setModel(selectedModel.substring(7));
                            } else if (selectedModel.startsWith("giga:")) {
                                gigaChatService.setModel(selectedModel.substring(5));
                            } else if (selectedModel.startsWith("deepseek:")) {
                                deepSeekService.setModel(selectedModel.substring(9));
                            }
                        }
                        log.info("Default model not found, selected first available: {}", selectedModel);
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = "# Добро пожаловать в GigaMeter\n\n" +
                "Я помогу с вашим планом тестирования JMeter: отвечу на вопросы, подскажу элементы и дам рекомендации по оптимизации.\n\n" +
                "**Специальные команды:**\n" +
                "- `@this` — информация о выбранном элементе\n" +
                "- `@optimize` — рекомендации по оптимизации выбранного элемента\n" +
                "- `@lint` — переименование элементов в плане\n" +
                "- `@wrap` — группировка HTTP Sampler под Transaction Controller\n" +
                "- `@usage` — статистика использования AI\n\n" +
                "Чем помочь?";

        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    /**
     * Starts a new conversation by clearing the chat area and conversation history.
     */
    private void startNewConversation() {
        log.info("Starting new conversation");

        // Clear the chat area
        chatArea.setText("");

        // Clear the conversation history
        conversationHistory.clear();

        // Reset the last command type
        lastCommandType = LastCommandType.NONE;

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Sends the message from the input field to the chat.
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        log.info("Sending user message: {}", message);

        // Add the user message to the chat
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "Вы: " + message, Color.BLACK, false);
        } catch (BadLocationException e) {
            log.error("Error appending user message to chat", e);
        }

        // Add the user message to the conversation history
        conversationHistory.add(message);

        // Clear the message field
        messageField.setText("");

        // Add thinking indicator
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "GigaMeter думает...", Color.GRAY, false);
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }

        // Check for special commands
        if (message.trim().startsWith("@this")) {
            handleThisCommand();
            return;
        } else if (message.trim().startsWith("@optimize")) {
            handleOptimizeCommand();
            return;
        } else if (message.trim().startsWith("@code")) {
            // @code command is disabled - use right-click context menu instead
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Команда @code отключена. Используйте контекстное меню в редакторе JSR223 (правый клик).",
                        Color.RED, false);

                // Re-enable input
                messageField.setEnabled(true);
                sendButton.setEnabled(true);
                messageField.requestFocusInWindow();
            } catch (BadLocationException e) {
                log.error("Error displaying message", e);
            }
            return;
        } else if (message.trim().startsWith("@lint")) {
            handleLintCommand(message);
            return;
        } else if (message.trim().startsWith("@wrap")) {
            handleWrapCommand();
            return;
        } else if (message.trim().startsWith("@usage")) {
            handleUsageCommand();
            return;
        } else if (message.trim().startsWith("@plan")) {
            handlePlanCommand(message);
            return;
        } else if (message.trim().startsWith("@rollback")) {
            handleRollbackCommand();
            return;
        }

        log.info("Checking if message is an element request: '{}'", message);

        // Disable input while processing
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        String elementResponse = JMeterElementRequestHandler.processElementRequest(message);

        // Only process as an element request if it's a valid request
        // This prevents general conversation from being interpreted as element requests
        if (elementResponse != null && !elementResponse.contains("I couldn't understand what to do with")) {
            log.info("Detected element request, response: '{}'",
                    elementResponse.length() > 50 ? elementResponse.substring(0, 50) + "..." : elementResponse);

            // Remove the loading indicator since we're about to display the response
            removeLoadingIndicator();
            processAiResponse(elementResponse);

            // Re-enable input after processing
            messageField.setEnabled(true);
            sendButton.setEnabled(true);
            messageField.requestFocusInWindow();

            return;
        }

        log.info("Message not recognized as an element request, processing as regular AI request");

        // Process the message in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return getAiResponse(message);
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the AI response
                    String response = get();

                    // Process the AI response
                    processAiResponse(response);

                    // Add the AI response to the conversation history
                    conversationHistory.add(response);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting AI response", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось обработать запрос. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @this command to get information about the currently selected
     * element.
     */
    private void handleThisCommand() {
        log.info("Processing @this command");

        // Reset the last command type since this is not a lint or wrap command
        lastCommandType = LastCommandType.NONE;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String elementInfo = getCurrentElementInfo();
                if (elementInfo == null) {
                    return "В плане тестирования не выбран элемент. Выберите элемент и повторите запрос.";
                }
                return elementInfo;
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the element info
                    String info = get();

                    // Process the response
                    processAiResponse(info);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting element info", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось получить информацию об элементе. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @optimize command to get optimization suggestions for the test
     * plan.
     */
    private void handleOptimizeCommand() {
        log.info("Processing @optimize command");

        // Reset the last command type since this is not a lint or wrap command
        lastCommandType = LastCommandType.NONE;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                AiService serviceToUse = getServiceForSelectedModel();
                return OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(serviceToUse);
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the optimization suggestions
                    String suggestions = get();

                    // Process the response
                    processAiResponse(suggestions);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting optimization suggestions", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось получить рекомендации по оптимизации. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Gets information about the currently selected element.
     * 
     * @return Information about the currently selected element, or null if no
     *         element is selected
     */
    public String getCurrentElementInfo() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.warn("Cannot get element info: GuiPackage is null");
                return null;
            }

            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.warn("No node is currently selected in the test plan");
                return null;
            }

            // Get the test element
            TestElement element = currentNode.getTestElement();
            if (element == null) {
                log.warn("Selected node does not have a test element");
                return null;
            }

            // Build information about the element
            StringBuilder info = new StringBuilder();
            info.append("# ").append(currentNode.getName()).append(" (").append(element.getClass().getSimpleName())
                    .append(")\n\n");

            // Add description based on element type
            String elementType = element.getClass().getSimpleName();
            info.append(JMeterElementManager.getElementDescription(elementType)).append("\n\n");

            // Add properties
            info.append("## Properties\n\n");

            // Get all property names
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();

                // Skip empty properties and internal JMeter properties
                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.")
                        && !propertyName.equals("guiclass")) {
                    // Format the property name for better readability
                    String formattedName = propertyName.replace(".", " ").replace("_", " ");
                    formattedName = formattedName.substring(0, 1).toUpperCase() + formattedName.substring(1);

                    info.append("- **").append(formattedName).append("**: ").append(propertyValue).append("\n");
                }
            }

            // Add hierarchical information
            info.append("\n## Location in Test Plan\n\n");

            // Get parent node
            TreeNode parent = currentNode.getParent();
            if (parent instanceof JMeterTreeNode) {
                JMeterTreeNode parentNode = (JMeterTreeNode) parent;
                info.append("- Parent: **").append(parentNode.getName()).append("** (")
                        .append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            }

            // Get child nodes
            if (currentNode.getChildCount() > 0) {
                info.append("- Children: ").append(currentNode.getChildCount()).append("\n");
                for (int i = 0; i < currentNode.getChildCount(); i++) {
                    JMeterTreeNode childNode = (JMeterTreeNode) currentNode.getChildAt(i);
                    info.append("  - **").append(childNode.getName()).append("** (")
                            .append(childNode.getTestElement().getClass().getSimpleName()).append(")\n");
                }
            } else {
                info.append("- No children\n");
            }

            // Add suggestions for what can be added to this element
            info.append("\n## Suggested Elements\n\n");
            String[][] suggestions = getContextAwareSuggestions(currentNode.getStaticLabel());
            if (suggestions.length > 0) {
                info.append("You can add the following elements to this node:\n\n");
                for (String[] suggestion : suggestions) {
                    info.append("- ").append(suggestion[0]).append("\n");
                }
            } else {
                info.append("No specific suggestions for this element type.\n");
            }

            return info.toString();
        } catch (Exception e) {
            log.error("Error getting current element info", e);
            return "Ошибка получения информации об элементе: " + e.getMessage();
        }
    }

    /**
     * Gets context-aware element suggestions based on the node type.
     * 
     * @param nodeType The type of node to get suggestions for
     * @return An array of string arrays, each containing [displayName]
     */
    private String[][] getContextAwareSuggestions(String nodeType) {
        // Convert to lowercase for case-insensitive comparison
        String type = nodeType.toLowerCase();

        // Return suggestions based on the node type
        if (type.contains("test plan")) {
            return new String[][] {
                    { "Thread Group" },
                    { "HTTP Cookie Manager" },
                    { "HTTP Header Manager" }
            };
        } else if (type.contains("thread group")) {
            return new String[][] {
                    { "HTTP Request" },
                    { "Loop Controller" },
                    { "If Controller" }
            };
        } else if (type.contains("http request")) {
            return new String[][] {
                    { "Response Assertion" },
                    { "JSON Extractor" },
                    { "Constant Timer" }
            };
        } else if (type.contains("controller")) {
            return new String[][] {
                    { "HTTP Request" },
                    { "Debug Sampler" },
                    { "JSR223 Sampler" }
            };
        } else {
            // Default suggestions
            return new String[][] {
                    { "Thread Group" },
                    { "HTTP Request" },
                    { "Response Assertion" }
            };
        }
    }

    /**
     * Removes the loading indicator from the chat area.
     */
    private void removeLoadingIndicator() {
        log.info("Attempting to remove loading indicator");
        try {
            StyledDocument doc = chatArea.getStyledDocument();

            // Find the loading indicator text
            String text = doc.getText(0, doc.getLength());
            int index = text.lastIndexOf("GigaMeter думает...");

            log.info("Loading indicator found at index: {}", index);

            if (index != -1) {
                // Remove the loading indicator
                doc.remove(index, "GigaMeter думает...".length());
                log.info("Loading indicator removed");
            } else {
                log.warn("Loading indicator not found in chat text");
            }
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }

    /**
     * Processes an AI response and displays it in the chat area.
     * 
     * @param response The AI response to process
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Нет ответа от AI. Попробуйте ещё раз.", Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            log.warn("Empty AI response");
            return;
        }

        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));

        // Add the AI response to the chat
        log.info("Appending AI response to chat");
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }

        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            log.info("Creating element buttons for context-aware suggestions");

            // Make sure the navigation panel is visible
            navigationPanel.setVisible(true);

            // Process the response to create element buttons
            elementSuggestionManager.createElementButtons(response);

            // Ensure the navigation panel is visible and properly laid out
            navigationPanel.revalidate();
            navigationPanel.repaint();

            // Log the number of components in the navigation panel
            log.info("Navigation panel now has {} components", navigationPanel.getComponentCount());

            // Scroll to the bottom of the chat area to show the latest message
            SwingUtilities.invokeLater(() -> {
                JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
                if (scrollPane != null) {
                    JScrollBar vertical = scrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            });
        });
    }

    private void handleUsageCommand() {
        log.info("Processing @usage command");
        // Get the currently selected model from the dropdown
        String selectedModel = (String) modelSelector.getSelectedItem();

        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Determine which service to use based on the model ID
        AiService serviceToUse = null;
        if (selectedModel != null) {
            if (selectedModel.startsWith("openai:")) {
                serviceToUse = openAiService;
            } else if (selectedModel.startsWith("giga:")) {
                serviceToUse = gigaChatService;
            } else if (selectedModel.startsWith("deepseek:")) {
                serviceToUse = deepSeekService;
            }
        }
        AiService finalServiceToUse = serviceToUse;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                UsageCommandHandler usageCommandHandler = new UsageCommandHandler();
                return usageCommandHandler.processUsageCommand(finalServiceToUse);
            }

            @Override
            protected void done() {
                try {
                    removeLoadingIndicator();
                    processAiResponse(get());
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing usage command", e);
                    removeLoadingIndicator();
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось выполнить команду @usage. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    private void handlePlanCommand(String message) {
        log.info("Processing @plan command");
        String normalized = message == null ? "" : message.trim();
        if ("@plan apply".equalsIgnoreCase(normalized)) {
            lastCommandType = LastCommandType.PLAN_APPLY;
        } else {
            lastCommandType = LastCommandType.NONE;
        }

        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                AiService serviceToUse = getServiceForSelectedModel();
                PlanCommandHandler planCommandHandler = new PlanCommandHandler(serviceToUse);
                return planCommandHandler.processPlanCommand(message);
            }

            @Override
            protected void done() {
                try {
                    removeLoadingIndicator();
                    processAiResponse(get());
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing @plan command", e);
                    removeLoadingIndicator();
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Failed to process @plan command. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying @plan error message", ex);
                    }
                } finally {
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    private void handleRollbackCommand() {
        log.info("Processing @rollback command");

        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                switch (lastCommandType) {
                    case PLAN_APPLY:
                        return PlanCommandHandler.undoLastAppliedPlan();
                    case LINT:
                        return new LintCommandHandler(getServiceForSelectedModel()).undoLastRename();
                    case WRAP:
                        return WrapUndoRedoHandler.getInstance().undoLastWrap();
                    default:
                        String planRollback = PlanCommandHandler.undoLastAppliedPlan();
                        if (!planRollback.startsWith("Nothing")) {
                            return planRollback;
                        }
                        String lintRollback = new LintCommandHandler(getServiceForSelectedModel()).undoLastRename();
                        if (!lintRollback.startsWith("Nothing")) {
                            return lintRollback;
                        }
                        String wrapRollback = WrapUndoRedoHandler.getInstance().undoLastWrap();
                        if (!wrapRollback.startsWith("Nothing")) {
                            return wrapRollback;
                        }
                        return "Nothing to rollback.";
                }
            }

            @Override
            protected void done() {
                try {
                    removeLoadingIndicator();
                    processAiResponse(get());
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing @rollback command", e);
                    removeLoadingIndicator();
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Failed to process @rollback command. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying @rollback error message", ex);
                    }
                } finally {
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @lint command to rename elements in the test plan.
     * 
     * @param message The message containing the @lint command
     */
    /**
     * Handles the @wrap command to group HTTP request samplers under Transaction
     * Controllers.
     */
    private void handleWrapCommand() {
        log.info("Processing @wrap command");

        // Set the last command type to WRAP
        lastCommandType = LastCommandType.WRAP;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                WrapCommandHandler wrapCommandHandler = new WrapCommandHandler();
                return wrapCommandHandler.processWrapCommand();
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the wrap result
                    String result = get();

                    // Process the response
                    processAiResponse(result);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing @wrap command", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось выполнить команду @wrap. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @lint command to rename elements in the test plan.
     * 
     * @param message The message containing the @lint command
     */
    private void handleLintCommand(String message) {
        log.info("Processing @lint command");

        // Set the last command type to LINT
        lastCommandType = LastCommandType.LINT;

        try {
            // Add user message to chat
            messageProcessor.appendMessage(chatArea.getStyledDocument(), message, Color.BLACK, true);
        } catch (BadLocationException e) {
            log.error("Error adding message to chat", e);
        }

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the currently selected model
                String selectedModel = (String) modelSelector.getSelectedItem();
                if (selectedModel == null) {
                    return "Сначала выберите модель.";
                }

                AiService serviceToUse = getServiceForSelectedModel();

                // Use the LintCommandHandler to process the lint command
                LintCommandHandler lintCommandHandler = new LintCommandHandler(serviceToUse);
                return lintCommandHandler.processLintCommand(message);
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the response
                    String response = get();

                    // Process the response
                    processAiResponse(response);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing lint command", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось выполнить команду @lint. Попробуйте ещё раз.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Gets an AI response for a message.
     * 
     * @param message The message to get a response for
     * @return The AI response
     */
    private String getAiResponse(String message) {
        log.info("Getting AI response for message: {}", message);

        // Get the currently selected model from the dropdown
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            log.warn("No model selected in dropdown, using default OpenAI model: {}",
                    openAiService.getCurrentModel());
            return openAiService.generateResponse(new ArrayList<>(conversationHistory));
        }

        // Get the model ID
        log.info("Using model from dropdown for message: {}", selectedModel);

        // Check if this is an OpenAI model (prefixed with "openai:")
        if (selectedModel.startsWith("openai:")) {
            // Extract the actual OpenAI model ID
            String openAiModelId = selectedModel.substring(7); // Remove "openai:" prefix
            log.info("Using OpenAI model: {}", openAiModelId);

            // Set the model in the OpenAI service
            openAiService.setModel(openAiModelId);

            // Call OpenAI API with conversation history
            return openAiService.generateResponse(new ArrayList<>(conversationHistory));
        } else if (selectedModel.startsWith("giga:")) {
            String gigaModelId = selectedModel.substring(5); // Remove "giga:" prefix
            log.info("Using GigaChat model: {}", gigaModelId);
            gigaChatService.setModel(gigaModelId);
            return gigaChatService.generateResponse(new ArrayList<>(conversationHistory));
        } else if (selectedModel.startsWith("deepseek:")) {
            String deepSeekModelId = selectedModel.substring(9); // Remove "deepseek:" prefix
            log.info("Using DeepSeek model: {}", deepSeekModelId);
            deepSeekService.setModel(deepSeekModelId);
            return deepSeekService.generateResponse(new ArrayList<>(conversationHistory));
        }

        log.warn("Unsupported model prefix: {}, fallback to OpenAI", selectedModel);
        return openAiService.generateResponse(new ArrayList<>(conversationHistory));
    }

    /**
     * Undoes the last rename operation performed by the ElementRenamer.
     */
    private void undoLastRename() {
        // Create a SwingWorker to process the undo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the currently selected model
                String selectedModel = (String) modelSelector.getSelectedItem();
                if (selectedModel == null) {
                    return "Сначала выберите модель.";
                }

                AiService serviceToUse = getServiceForSelectedModel();

                // Create a LintCommandHandler and process the undo
                LintCommandHandler lintHandler = new LintCommandHandler(serviceToUse);
                return lintHandler.undoLastRename();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying undo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error undoing rename operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Ошибка отмены переименования: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Redoes the last undone rename operation performed by the ElementRenamer.
     */
    private void redoLastUndo() {
        // Create a SwingWorker to process the redo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Create a LintCommandHandler and process the redo
                LintCommandHandler lintHandler = new LintCommandHandler(getServiceForSelectedModel());
                return lintHandler.redoLastUndo();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying redo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error redoing rename operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Ошибка повтора переименования: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Undoes the last wrap operation performed by the WrapCommandHandler.
     */
    private void undoLastWrap() {
        // Create a SwingWorker to process the undo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the WrapUndoRedoHandler instance and process the undo
                return WrapUndoRedoHandler.getInstance().undoLastWrap();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying wrap undo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error undoing wrap operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Ошибка отмены @wrap: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Cleans up resources when the panel is no longer needed.
     */
    public void cleanup() {
        // Unregister property change listener
        UIManager.removePropertyChangeListener(this);
    }

    /**
     * Updates the font sizes of chat components based on JMeter's current scale
     * factor
     */
    private void updateFontSizes() {
        float scale = JMeterUIDefaults.INSTANCE.getScale();

        // Update chat area font
        Font currentChatFont = chatArea.getFont();
        float newChatSize = baseChatFontSize * scale;
        Font newChatFont = currentChatFont.deriveFont(newChatSize);
        chatArea.setFont(newChatFont);

        // Update message field font
        Font currentMessageFont = messageField.getFont();
        float newMessageSize = baseMessageFontSize * scale;
        Font newMessageFont = currentMessageFont.deriveFont(newMessageSize);
        messageField.setFont(newMessageFont);
    }

    private AiService getServiceForSelectedModel() {
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            return openAiService;
        }
        if (selectedModel.startsWith("openai:")) {
            return openAiService;
        }
        if (selectedModel.startsWith("giga:")) {
            return gigaChatService;
        }
        if (selectedModel.startsWith("deepseek:")) {
            return deepSeekService;
        }
        return openAiService;
    }

    /**
     * Handles property change events, specifically for UI refresh events triggered
     * by zoom actions
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        // Check if this is a UI refresh event
        if ("lookAndFeel".equals(evt.getPropertyName())) {
            // Update font sizes based on the current scale
            updateFontSizes();
        }
    }
}

