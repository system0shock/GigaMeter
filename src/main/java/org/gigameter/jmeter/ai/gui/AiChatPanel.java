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
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.gigameter.jmeter.ai.utils.PlanContextBuilder;
import org.gigameter.jmeter.ai.service.cli.CliAiService;
import org.gigameter.jmeter.ai.skills.SkillService;
import org.gigameter.jmeter.ai.service.ops.FencedOpsExtractor;
import org.gigameter.jmeter.ai.service.ops.OpsApplier;
import org.gigameter.jmeter.ai.service.ops.OpsApplyResult;
import org.gigameter.jmeter.ai.service.ops.OpsException;
import org.gigameter.jmeter.ai.service.ops.OpsPreviewRenderer;
import org.gigameter.jmeter.ai.service.ops.OpsProtocol;
import org.gigameter.jmeter.ai.service.ops.OpsUndoStore;
import org.gigameter.jmeter.ai.service.ops.PlanOp;
import org.gigameter.jmeter.ai.service.ops.PlanOpsParser;
import org.gigameter.jmeter.ai.service.ops.PlanOpsValidator;
import org.gigameter.jmeter.ai.utils.VersionUtils;
import org.gigameter.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.gigameter.jmeter.ai.lint.LintCommandHandler;
import org.gigameter.jmeter.ai.wrap.WrapCommandHandler;
import org.gigameter.jmeter.ai.wrap.WrapUndoRedoHandler;
import org.gigameter.jmeter.ai.service.QwenCodeCliService;
import org.gigameter.jmeter.ai.service.GigaCodeCliService;
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
    private JButton stopButton;
    private JButton newChatButton;
    private JButton rollbackButton;
    /** The in-flight chat request, so a Stop action can cancel it. */
    private volatile SwingWorker<?, ?> activeWorker;
    private JComboBox<String> modelSelector;
    private JCheckBox contextToggle;
    private JComboBox<String> contextModeSelector;
    private List<String> conversationHistory;
    private DeepSeekService deepSeekService;
    private GigaChatService gigaChatService;
    private QwenCodeCliService qwenCodeCliService;
    private GigaCodeCliService gigaCodeCliService;
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
        PLAN_APPLY,
        CLI_OPS
    }

    private LastCommandType lastCommandType = LastCommandType.NONE;

    /** Tree revision hash sent to the CLI agent on the last turn; guards stale-id edits. */
    private volatile String lastCliOpsRevision;

    /** Tree revision last sent to a CLI agent; lets session turns skip re-sending unchanged context. */
    private volatile String lastSentTreeRevision;

    /** True when the last response was rendered incrementally via streaming (avoid re-render). */
    private volatile boolean lastResponseStreamed;
    /** Count of characters streamed live this turn (0 ⇒ nothing shown, render the final text). */
    private volatile int streamedChars;
    /** Document offset where this turn's streamed text began (to wipe raw ops JSON before applying). */
    private volatile int cliStreamStartOffset;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        // Initialize services and utilities
        deepSeekService = new DeepSeekService();
        gigaChatService = new GigaChatService();
        qwenCodeCliService = new QwenCodeCliService();
        gigaCodeCliService = new GigaCodeCliService();
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
                if (selectedModel.startsWith("giga:")) {
                    gigaChatService.setModel(selectedModel.substring(5));
                } else if (selectedModel.startsWith("deepseek:")) {
                    deepSeekService.setModel(selectedModel.substring(9));
                }
            }
            updateContextControlsVisibility();
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, getBorderColor()));

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
        chatArea.setForeground(getPrimaryTextColor());

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
                                    getInfoTextColor(), false);
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
                                            getInfoTextColor(), false);
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
        contextToggle = new JCheckBox("Контекст");
        contextToggle.setSelected(isContextEnabledByProperty());
        contextToggle.setToolTipText("Добавлять контекст JMeter к обычным сообщениям чата");
        modelPanel.add(contextToggle);
        contextModeSelector = new JComboBox<>();
        contextModeSelector.addItem("selected");
        contextModeSelector.addItem("plan");
        contextModeSelector.addItem("selected+plan");
        contextModeSelector.setSelectedItem(getContextModeByProperty());
        contextModeSelector.setToolTipText("Режим контекста для обычного чата");
        contextModeSelector.setEnabled(contextToggle.isSelected());
        contextToggle.addActionListener(e -> contextModeSelector.setEnabled(contextToggle.isSelected()));
        modelPanel.add(contextModeSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);

        // Create the navigation panel for tree navigation and element buttons
        navigationPanel = new JPanel();
        navigationPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("Подсказки элементов"));
        navigationPanel.setBackground(uiColor("Panel.background", new Color(245, 245, 250)));

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
        messageField.setForeground(getPrimaryTextColor());

        // Store the base font size for scaling
        baseMessageFontSize = largerFont.getSize2D();
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(getBorderColor()),
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

        // Initialize send + stop buttons (stop is shown only while a request is in flight)
        sendButton = new JButton("Отправить");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(e -> sendMessage());

        stopButton = new JButton("Стоп");
        stopButton.setFont(new Font(stopButton.getFont().getName(), Font.BOLD, 12));
        stopButton.setFocusPainted(false);
        stopButton.setToolTipText("Прервать текущий запрос");
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> cancelActiveRequest());

        JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.add(sendButton, BorderLayout.CENTER);
        buttonPanel.add(stopButton, BorderLayout.SOUTH);
        inputPanel.add(buttonPanel, BorderLayout.EAST);

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
                BorderFactory.createMatteBorder(1, 0, 1, 0, getBorderColor()),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(uiColor("Panel.background", new Color(240, 240, 240)));

        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("GigaMeter v" + VersionUtils.getVersion());
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Create the "New Chat" button with a plus icon
        newChatButton = new JButton("+");
        newChatButton.setToolTipText("Начать новый диалог");
        newChatButton.setFont(new Font(newChatButton.getFont().getName(), Font.BOLD, 16));
        newChatButton.setFocusPainted(false);
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        newChatButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(getBorderColor(), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());

        // "Откатить" button — runs the same logic as @rollback (undo the agent's last change).
        rollbackButton = new JButton("↶ Откатить");
        rollbackButton.setToolTipText("Откатить последнее изменение, внесённое агентом");
        rollbackButton.setFocusPainted(false);
        rollbackButton.setMargin(new Insets(0, 8, 0, 8));
        rollbackButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(getBorderColor(), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        rollbackButton.addActionListener(e -> handleRollbackCommand());

        // Group the action buttons on the right side of the header.
        JPanel headerButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        headerButtons.setOpaque(false);
        headerButtons.add(rollbackButton);
        headerButtons.add(newChatButton);
        headerPanel.add(headerButtons, BorderLayout.EAST);

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

                // Cloud providers (DeepSeek/GigaChat) are hidden by default — the plugin is
                // CLI-first. Re-enable with gigameter.providers.cloud.enabled=true.
                boolean cloudEnabled = "true".equalsIgnoreCase(
                        AiConfig.getProperty("gigameter.providers.cloud.enabled", "false"));

                // Add DeepSeek models
                if (cloudEnabled)
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
                if (cloudEnabled)
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

                // Add local CLI providers when their command is configured, one entry per model from
                // <prefix>.cli.models (qwen has no "list models" API). Falls back to a single
                // ":default" entry (the CLI's own configured model) when no list is given.
                if (!AiConfig.getProperty("qwen.cli.command", "").trim().isEmpty()) {
                    for (String m : cliModelList("qwen")) {
                        allModels.add("qwen-cli:" + m);
                    }
                    log.info("Added Qwen Code CLI models to selector");
                }
                if (!AiConfig.getProperty("gigacode.cli.command", "").trim().isEmpty()) {
                    for (String m : cliModelList("gigacode")) {
                        allModels.add("gigacode-cli:" + m);
                    }
                    log.info("Added GigaCode CLI models to selector");
                }

                return allModels;
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    // Get the default model ID from configured provider. CLI-first: default to qwen.
                    String defaultService = AiConfig.getProperty("jmeter.ai.service.type", "qwen");
                    String defaultModelId;
                    if ("deepseek".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "deepseek:" + deepSeekService.getCurrentModel();
                    } else if ("giga".equalsIgnoreCase(defaultService)
                            || "gigachat".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "giga:" + gigaChatService.getCurrentModel();
                    } else if ("cli".equalsIgnoreCase(defaultService)
                            || "qwen".equalsIgnoreCase(defaultService)
                            || "qwen-cli".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "qwen-cli:" + cliModelList("qwen").get(0);
                    } else if ("gigacode".equalsIgnoreCase(defaultService)
                            || "gigacode-cli".equalsIgnoreCase(defaultService)) {
                        defaultModelId = "gigacode-cli:" + cliModelList("gigacode").get(0);
                    } else {
                        defaultModelId = "qwen-cli:" + cliModelList("qwen").get(0);
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
                            if (selectedModel.startsWith("giga:")) {
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
                "Опишите задачу обычным языком — отвечу на вопросы по тест-плану, проанализирую его и " +
                "могу вносить изменения в дерево с предпросмотром и подтверждением.\n\n" +
                "**Команды:**\n" +
                "- `@plan <сценарий>` — создать или дополнить тест-план\n" +
                "- `@lint` — привести имена элементов к единым правилам\n" +
                "- `@optimize` — рекомендации по оптимизации\n" +
                "- `@rollback` — откатить последнее изменение агента\n\n" +
                "Чем помочь?";

        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, getInfoTextColor(), true);
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

        // Drop any persistent CLI sessions so the new chat starts fresh
        try { qwenCodeCliService.resetSession(); } catch (RuntimeException ignored) { /* best effort */ }
        try { gigaCodeCliService.resetSession(); } catch (RuntimeException ignored) { /* best effort */ }
        lastSentTreeRevision = null;

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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "Вы: " + message, getPrimaryTextColor(), false);
        } catch (BadLocationException e) {
            log.error("Error appending user message to chat", e);
        }

        // Add the user message to the conversation history
        conversationHistory.add(message);

        // Clear the message field
        messageField.setText("");

        // Add thinking indicator
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "GigaMeter думает...", getSecondaryTextColor(), false);
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }

        // In CLI mode, @lint/@plan/@optimize run as editable "skills" through the CLI ops protocol
        // instead of the legacy cloud handlers: rewrite the turn to the skill prompt + the user's
        // args, then fall through to the normal CLI send path (streaming + ops apply).
        String skillId = skillCommandId(message);
        if (isCliModelSelected() && skillId != null) {
            String composed = composeSkillPrompt(skillId, message);
            conversationHistory.set(conversationHistory.size() - 1, composed);
            // fall through (do NOT return) to the regular send flow below
        } else
        // Check for special commands
        if (message.trim().startsWith("@this")) {
            handleThisCommand(message);
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

        // CLI agents drive editing through the jmeter-ops protocol, not the regex sentinel handler;
        // skip the keyword interception so "add a thread group" reaches the agent verbatim.
        String elementResponse = isCliModelSelected()
                ? null : JMeterElementRequestHandler.processElementRequest(message);

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
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return getAiResponse(message);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    // Stop button already cleaned up the UI and killed the subprocess.
                    return;
                }
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the AI response
                    String response = get();

                    // If the reply was streamed live but nothing was actually shown (e.g. an error,
                    // or no partial deltas), fall back to rendering the final text normally.
                    boolean streamed = lastResponseStreamed && streamedChars > 0;

                    // CLI agents may answer with a jmeter-ops edit block — handle it specially
                    // (preview, confirm, apply) instead of just printing the JSON.
                    if (isCliModelSelected() && FencedOpsExtractor.hasOps(response)) {
                        handleCliOpsResponse(response, streamed);
                    } else if (streamed && response != null && response.startsWith("Error:")) {
                        // The reply streamed some text but ended in an error — surface it instead of
                        // leaving the user staring at a half-finished message with no explanation.
                        processAiResponse(response);
                    } else if (streamed) {
                        // Already rendered incrementally; just separate from the next message.
                        appendStreamingNewline();
                    } else {
                        // Process the AI response
                        processAiResponse(response);
                    }

                    // Add the AI response to the conversation history
                    conversationHistory.add(response);
                } catch (java.util.concurrent.CancellationException e) {
                    log.info("Request was cancelled by user");
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
                } finally {
                    setRequestInProgress(false);
                }
            }
        };
        activeWorker = worker;
        setRequestInProgress(true);
        worker.execute();
    }

    /** Toggles input/send/stop controls for an in-flight async request. */
    private void setRequestInProgress(boolean inProgress) {
        messageField.setEnabled(!inProgress);
        sendButton.setEnabled(!inProgress);
        if (stopButton != null) {
            stopButton.setVisible(inProgress);
        }
        if (newChatButton != null) {
            newChatButton.setEnabled(!inProgress); // avoid resetting history mid-request
        }
        if (rollbackButton != null) {
            rollbackButton.setEnabled(!inProgress);
        }
        if (!inProgress) {
            activeWorker = null;
            messageField.requestFocusInWindow();
        }
    }

    /** Cancels the in-flight chat request and force-kills any CLI subprocess. */
    private void cancelActiveRequest() {
        SwingWorker<?, ?> w = activeWorker;
        if (w != null) {
            w.cancel(true);
        }
        // Kill any live CLI process tree (the worker interrupt alone won't stop the child).
        try { qwenCodeCliService.cancel(); } catch (RuntimeException ignored) { /* best effort */ }
        try { gigaCodeCliService.cancel(); } catch (RuntimeException ignored) { /* best effort */ }
        removeLoadingIndicator();
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(),
                    "Запрос прерван.", Color.GRAY, false);
        } catch (BadLocationException ignored) {
            // non-fatal
        }
        setRequestInProgress(false);
    }

    /** Cancels in-flight work and kills CLI subprocesses; call when the panel is being closed. */
    public void shutdown() {
        SwingWorker<?, ?> w = activeWorker;
        if (w != null) {
            w.cancel(true);
        }
        try { qwenCodeCliService.cancel(); } catch (RuntimeException ignored) { /* best effort */ }
        try { gigaCodeCliService.cancel(); } catch (RuntimeException ignored) { /* best effort */ }
    }

    /**
     * Handles the @this command to get information about the currently selected
     * element.
     */
    private void handleThisCommand(String message) {
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
                GuiPackage gp = GuiPackage.getInstance();
                JMeterTreeNode currentNode = gp != null ? gp.getTreeListener().getCurrentNode() : null;
                TestElement element = currentNode != null ? currentNode.getTestElement() : null;

                if (element == null) {
                    return "В плане тестирования не выбран элемент. Выберите элемент и повторите запрос.";
                }

                AiService serviceToUse = getServiceForSelectedModel();
                if (serviceToUse == null) {
                    return getCurrentElementInfo();
                }

                String question = extractThisQuery(message);
                boolean isJsr223 = element.getClass().getSimpleName().contains("JSR223");

                try {
                    List<String> prompt = new ArrayList<>();
                    if (isJsr223) {
                        prompt.add(buildJsr223ExplainPrompt(currentNode, element, question));
                    } else {
                        String elementInfo = getCurrentElementInfo();
                        if (elementInfo == null) return "Не удалось получить информацию об элементе.";
                        prompt.add(question.isEmpty()
                                ? buildThisSummaryPrompt(elementInfo)
                                : buildThisQuestionPrompt(question, elementInfo));
                    }
                    String aiResponse = serviceToUse.generateResponse(prompt);
                    return (aiResponse == null || aiResponse.trim().isEmpty()) ? getCurrentElementInfo() : aiResponse;
                } catch (Throwable e) {
                    log.warn("Failed to process @this with AI, fallback to static info", e);
                    return getCurrentElementInfo();
                }
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

            info.append("Свойства:\n");

            // Get all property names
            PropertyIterator propertyIterator = element.propertyIterator();
            int shownProperties = 0;
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyType = property.getClass().getSimpleName();
                if (propertyType.contains("CollectionProperty")
                        || propertyType.contains("MapProperty")
                        || propertyType.contains("TestElementProperty")) {
                    continue;
                }
                String propertyValue;
                try {
                    propertyValue = property.getStringValue();
                } catch (Throwable t) {
                    log.debug("Skipping property due to rendering error: {}", propertyName, t);
                    continue;
                }
                if (propertyValue == null) {
                    propertyValue = "";
                }
                if (propertyValue.length() > 500) {
                    propertyValue = propertyValue.substring(0, 500) + "...";
                }

                // Skip empty properties and internal JMeter properties
                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.")
                        && !propertyName.equals("guiclass")) {
                    // Format the property name for better readability
                    String formattedName = propertyName.replace(".", " ").replace("_", " ");
                    formattedName = formattedName.substring(0, 1).toUpperCase() + formattedName.substring(1);

                    info.append("- **").append(formattedName).append("**: ").append(propertyValue).append("\n");
                    shownProperties++;
                    if (shownProperties >= 40) {
                        info.append("- ... (property list truncated)\n");
                        break;
                    }
                }
            }

            info.append("\nРасположение:\n");

            TreeNode parent = currentNode.getParent();
            if (parent instanceof JMeterTreeNode) {
                JMeterTreeNode parentNode = (JMeterTreeNode) parent;
                info.append("- Родитель: **").append(parentNode.getName()).append("** (")
                        .append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");

                // Add siblings for context
                int siblingCount = parentNode.getChildCount();
                if (siblingCount > 1) {
                    info.append("- Соседние элементы (").append(siblingCount).append(" всего):\n");
                    for (int i = 0; i < siblingCount && i < 10; i++) {
                        JMeterTreeNode sibling = (JMeterTreeNode) parentNode.getChildAt(i);
                        String marker = sibling.equals(currentNode) ? " ← текущий" : "";
                        TestElement sibElem = sibling.getTestElement();
                        String sibType = sibElem != null ? sibElem.getClass().getSimpleName() : "?";
                        info.append("  ").append(i + 1).append(". **").append(sibling.getName())
                                .append("** (").append(sibType).append(")").append(marker).append("\n");
                    }
                    if (siblingCount > 10) {
                        info.append("  ... ещё ").append(siblingCount - 10).append(" элементов\n");
                    }
                }
            }

            if (currentNode.getChildCount() > 0) {
                info.append("- Дочерних элементов: ").append(currentNode.getChildCount()).append("\n");
                for (int i = 0; i < currentNode.getChildCount(); i++) {
                    JMeterTreeNode childNode = (JMeterTreeNode) currentNode.getChildAt(i);
                    info.append("  - **").append(childNode.getName()).append("** (")
                            .append(childNode.getTestElement().getClass().getSimpleName()).append(")\n");
                }
            }

            return info.toString();
        } catch (Throwable e) {
            log.error("Error getting current element info", e);
            return "Ошибка получения информации об элементе: " + e.getMessage();
        }
    }

    private String extractThisQuery(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replaceFirst("^@this\\s*", "").trim();
    }

    private String buildJsr223ExplainPrompt(JMeterTreeNode node, TestElement element, String userQuestion) {
        String name     = node.getName();
        String type     = element.getClass().getSimpleName();
        String language = element.getPropertyAsString("scriptLanguage");
        if (language == null || language.trim().isEmpty()) language = "groovy";
        String script   = element.getPropertyAsString("script");
        if (script == null) script = "";
        if (script.length() > 4000) script = script.substring(0, 4000) + "\n... (скрипт обрезан)";

        // Gather sibling context
        StringBuilder siblings = new StringBuilder();
        TreeNode parent = node.getParent();
        if (parent instanceof JMeterTreeNode) {
            JMeterTreeNode parentNode = (JMeterTreeNode) parent;
            siblings.append("Родитель: ").append(parentNode.getName())
                    .append(" (").append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            int count = parentNode.getChildCount();
            if (count > 1) {
                siblings.append("Соседние элементы:\n");
                for (int i = 0; i < count && i < 8; i++) {
                    JMeterTreeNode s = (JMeterTreeNode) parentNode.getChildAt(i);
                    String marker = s.equals(node) ? " ← текущий" : "";
                    siblings.append("  ").append(i + 1).append(". ").append(s.getName())
                            .append(" (").append(s.getTestElement().getClass().getSimpleName()).append(")")
                            .append(marker).append("\n");
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Ты ассистент по Apache JMeter. Отвечай строго на русском языке.\n");
        sb.append("Перед ответом мысленно разбери код скрипта. Выдай только финальный ответ без заголовков шагов.\n\n");

        if (script.trim().isEmpty()) {
            sb.append("Элемент \"").append(name).append("\" — JSR223 (").append(type).append("), язык: ").append(language).append(".\n");
            sb.append("Скрипт пустой.\n\n");
            if (!userQuestion.isEmpty()) {
                sb.append("Вопрос: ").append(userQuestion).append("\n");
            } else {
                sb.append("Сообщи пользователю, что скрипт пустой, и предложи написать его — уточни, что именно нужно сделать в этом шаге.\n");
            }
        } else {
            sb.append("Элемент \"").append(name).append("\" — JSR223 (").append(type).append("), язык: ").append(language).append(".\n");
            if (siblings.length() > 0) sb.append(siblings);
            sb.append("\nСкрипт:\n```").append(language).append("\n").append(script).append("\n```\n\n");

            if (!userQuestion.isEmpty()) {
                sb.append("Вопрос пользователя: ").append(userQuestion).append("\n\n");
                sb.append("Ответь по существу, опираясь на код выше.\n");
            } else {
                sb.append("Объясни этот скрипт:\n");
                sb.append("- Что делает (цель в контексте теста)\n");
                sb.append("- Какие JMeter-переменные читает (vars.get / props.get / Parameters)\n");
                sb.append("- Какие переменные записывает (vars.put / props.put)\n");
                sb.append("- Побочные эффекты (HTTP-запросы, файлы, логи, ctx.setCurrentSampler и т.п.)\n");
                sb.append("- Потенциальные проблемы или улучшения\n");
            }
        }
        return sb.toString();
    }

    private String buildThisSummaryPrompt(String elementInfo) {
        return "Ты ассистент по Apache JMeter. Отвечай строго на русском языке.\n" +
                "Перед ответом мысленно разберись: что это за элемент, какова его роль среди соседей, есть ли проблемы в настройке.\n" +
                "Выдай только финальный ответ — без заголовков шагов и без пересказа своего анализа.\n\n" +
                "Ответ должен содержать:\n" +
                "- Что этот элемент делает в тест-плане\n" +
                "- Ключевые настройки (из свойств ниже)\n" +
                "- На что стоит обратить внимание\n" +
                "Элемент:\n" + elementInfo;
    }

    private String buildThisQuestionPrompt(String question, String elementInfo) {
        return "Ты ассистент по Apache JMeter. Отвечай строго на русском языке.\n" +
                "Перед ответом мысленно разберись в вопросе и в данных об элементе.\n" +
                "Выдай только финальный ответ — без заголовков шагов, без пересказа вопроса.\n\n" +
                "Вопрос: " + question + "\n\n" +
                "Элемент:\n" + elementInfo;
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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, getInfoTextColor(), true);
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
            if (selectedModel.startsWith("giga:")) {
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
                                "Не удалось выполнить команду @plan. Попробуйте ещё раз.",
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
                    case CLI_OPS:
                        return undoCliOps();
                    default:
                        if (OpsUndoStore.canUndo()) {
                            return undoCliOps();
                        }
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
                    String result = get();
                    // The handlers use an English "Nothing..." sentinel for chaining — show it in Russian.
                    if (result != null && result.startsWith("Nothing")) {
                        result = "Нечего откатывать.";
                    }
                    processAiResponse(result);
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing @rollback command", e);
                    removeLoadingIndicator();
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Не удалось выполнить команду @rollback. Попробуйте ещё раз.",
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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), message, getPrimaryTextColor(), true);
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
        lastResponseStreamed = false;
        streamedChars = 0;
        List<String> requestConversation = buildConversationForAi();

        // Get the currently selected model from the dropdown
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            String m = cliModelList("qwen").get(0);
            log.warn("No model selected in dropdown, falling back to Qwen Code CLI, model: {}", m);
            return runCliProvider(qwenCodeCliService, requestConversation, m);
        }

        // Get the model ID
        log.info("Using model from dropdown for message: {}", selectedModel);

        if (selectedModel.startsWith("giga:")) {
            String gigaModelId = selectedModel.substring(5); // Remove "giga:" prefix
            log.info("Using GigaChat model: {}", gigaModelId);
            gigaChatService.setModel(gigaModelId);
            return gigaChatService.generateResponse(requestConversation);
        } else if (selectedModel.startsWith("deepseek:")) {
            String deepSeekModelId = selectedModel.substring(9); // Remove "deepseek:" prefix
            log.info("Using DeepSeek model: {}", deepSeekModelId);
            deepSeekService.setModel(deepSeekModelId);
            return deepSeekService.generateResponse(requestConversation);
        } else if (selectedModel.startsWith("qwen-cli:")) {
            String m = selectedModel.substring("qwen-cli:".length());
            log.info("Using Qwen Code CLI, model: {}", m);
            return runCliProvider(qwenCodeCliService, requestConversation, m);
        } else if (selectedModel.startsWith("gigacode-cli:")) {
            String m = selectedModel.substring("gigacode-cli:".length());
            log.info("Using GigaCode CLI, model: {}", m);
            return runCliProvider(gigaCodeCliService, requestConversation, m);
        }

        String m = cliModelList("qwen").get(0);
        log.warn("Unsupported model prefix: {}, falling back to Qwen Code CLI, model: {}", selectedModel, m);
        return runCliProvider(qwenCodeCliService, requestConversation, m);
    }

    /** Rolls back the last CLI ops batch on the EDT. */
    private String undoCliOps() {
        if (!OpsUndoStore.canUndo()) {
            return "Nothing to rollback.";
        }
        final int[] count = {0};
        Runnable body = () -> count[0] = OpsUndoStore.undoLast();
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(body);
            } catch (Exception e) {
                log.error("Failed to undo CLI ops", e);
                return "Не удалось откатить изменения агента: " + e.getMessage();
            }
        }
        GuiPackage gp = GuiPackage.getInstance();
        if (gp != null && gp.getMainFrame() != null) {
            gp.getMainFrame().repaint();
        }
        lastCommandType = LastCommandType.NONE;
        return "Откат изменений агента выполнен (операций: " + count[0] + ").";
    }

    /**
     * Runs a CLI provider, streaming the reply into the chat live when the provider supports it
     * (incremental text instead of a multi-second frozen wait). Falls back to a blocking call
     * otherwise. Always returns the full final reply for history/ops handling.
     */
    private String runCliProvider(CliAiService svc, List<String> fullConversation, String model) {
        // With an active session the agent already has the history; send only the new turn (plus
        // tree context if it changed). Otherwise send the full conversation with protocol + context.
        List<String> conv = svc.isSessionActive()
                ? buildCliSessionTurn()
                : withCliOpsContext(fullConversation);

        if (svc.supportsStreaming()) {
            lastResponseStreamed = true;
            SwingUtilities.invokeLater(() -> {
                removeLoadingIndicator();
                cliStreamStartOffset = chatArea.getStyledDocument().getLength();
            });
            return svc.generateResponseStreaming(conv, model,
                    delta -> SwingUtilities.invokeLater(() -> appendStreamingDelta(delta)));
        }
        return svc.generateResponse(conv, model);
    }

    /** Current plan tree + revision hash, or {@code [tree, revision]} with empties if unavailable. */
    private String[] currentTreeContext() {
        String tree = "";
        String revision = null;
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp != null && gp.getTreeModel() != null
                    && gp.getTreeModel().getRoot() instanceof JMeterTreeNode) {
                JMeterTreeNode root = JMeterPlanSerializer.planRoot(
                        (JMeterTreeNode) gp.getTreeModel().getRoot());
                SerializedPlan plan = JMeterPlanSerializer.serialize(
                        root, JMeterPlanSerializer.SKELETON_MAX_ELEMENTS,
                        JMeterPlanSerializer.DEFAULT_MAX_DEPTH);
                java.util.List<Integer> selected = selectedNodeIds(plan, gp);
                int threshold = Integer.parseInt(
                        AiConfig.getProperty("gigameter.context.collapse.threshold", "3"));
                int maxChars = Integer.parseInt(
                        AiConfig.getProperty("gigameter.context.max.chars", "24000"));
                tree = PlanContextBuilder.build(plan, selected, threshold, maxChars);
                revision = plan.revisionHash() + "#" + PlanContextBuilder.selectionHash(selected);
                log.info("CLI tree context (revision={}):\n{}", revision, tree);
            }
        } catch (Exception e) {
            log.debug("Failed to build CLI tree context", e);
        }
        return new String[] {tree, revision};
    }

    /** Whole-plan #ids of the currently mouse-selected nodes (identity match against nodeById). */
    private java.util.List<Integer> selectedNodeIds(SerializedPlan plan, GuiPackage gp) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        try {
            JMeterTreeNode[] selected = gp.getTreeListener().getSelectedNodes();
            if (selected == null) {
                return ids;
            }
            for (JMeterTreeNode sel : selected) {
                for (java.util.Map.Entry<Integer, JMeterTreeNode> en : plan.nodeById.entrySet()) {
                    if (en.getValue() == sel) {
                        ids.add(en.getKey());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read selected nodes", e);
        }
        return ids;
    }

    /**
     * Builds a single-turn request for an active CLI session: just the latest user message, plus the
     * tree context only when the tree changed since the last send (the agent still holds the rest).
     */
    private List<String> buildCliSessionTurn() {
        List<String> turn = new ArrayList<>(1);
        if (conversationHistory.isEmpty()) {
            return turn;
        }
        String latest = conversationHistory.get(conversationHistory.size() - 1);
        String[] ctx = currentTreeContext();
        String tree = ctx[0];
        String revision = ctx[1];
        this.lastCliOpsRevision = revision; // apply guard always uses the live revision
        if (revision != null && !revision.equals(lastSentTreeRevision)) {
            latest = latest + "\n\n" + OpsProtocol.buildContextBlock(tree, revision);
            this.lastSentTreeRevision = revision;
        }
        turn.add(latest);
        return turn;
    }

    /** Appends a streamed text chunk directly to the chat document (EDT only). */
    private void appendStreamingDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        try {
            javax.swing.text.StyledDocument doc = chatArea.getStyledDocument();
            doc.insertString(doc.getLength(), delta, null);
            chatArea.setCaretPosition(doc.getLength());
            streamedChars += delta.length();
        } catch (BadLocationException e) {
            log.debug("Failed to append streaming delta", e);
        }
    }

    /** Removes the text streamed live this turn (used to hide raw ops JSON before showing results). */
    private void clearStreamedRegion() {
        try {
            javax.swing.text.StyledDocument doc = chatArea.getStyledDocument();
            int from = Math.max(0, Math.min(cliStreamStartOffset, doc.getLength()));
            if (doc.getLength() > from) {
                doc.remove(from, doc.getLength() - from);
            }
        } catch (BadLocationException e) {
            log.debug("Failed to clear streamed region", e);
        }
    }

    /** Adds a blank line after a streamed reply so the next message is visually separated. */
    private void appendStreamingNewline() {
        try {
            javax.swing.text.StyledDocument doc = chatArea.getStyledDocument();
            doc.insertString(doc.getLength(), "\n\n", null);
        } catch (BadLocationException e) {
            log.debug("Failed to append streaming newline", e);
        }
    }

    /**
     * Drops {@code @}-command turns (e.g. {@code @rollback}, {@code @plan}) from the history sent to
     * a CLI agent. Those are handled by the plugin, not the model; leaving them in pollutes the
     * agent's context (it starts answering about {@code @rollback}) and skews role alternation.
     */
    private List<String> stripCommandTurns(List<String> base) {
        List<String> out = new ArrayList<>();
        if (base == null) {
            return out;
        }
        for (String turn : base) {
            if (turn != null && turn.trim().startsWith("@")) {
                continue;
            }
            out.add(turn);
        }
        return out;
    }

    /**
     * The list of model ids to offer for a CLI provider, from {@code <prefix>.cli.models}
     * (comma/space separated). Returns {@code ["default"]} when unset — meaning "use whatever model
     * the CLI itself is configured with" (no {@code --model} override).
     */
    private List<String> cliModelList(String prefix) {
        String raw = AiConfig.getProperty(prefix + ".cli.models", "").trim();
        List<String> models = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String part : raw.split("[,\\s]+")) {
                if (!part.trim().isEmpty()) {
                    models.add(part.trim());
                }
            }
        }
        if (models.isEmpty()) {
            models.add("default");
        }
        return models;
    }

    /** Returns the skill id if the message is a skill command (@lint/@plan/@optimize), else null. */
    private String skillCommandId(String message) {
        if (message == null) {
            return null;
        }
        String t = message.trim().toLowerCase();
        if (t.startsWith("@lint")) return "lint";
        if (t.startsWith("@plan")) return "plan";
        if (t.startsWith("@optimize")) return "optimize";
        return null;
    }

    /** Builds the prompt sent to the CLI agent for a skill: its (editable) instructions + user args. */
    private String composeSkillPrompt(String skillId, String message) {
        String args = message == null ? "" : message.trim();
        int sp = args.indexOf(' ');
        args = sp >= 0 ? args.substring(sp + 1).trim() : "";
        String prompt = SkillService.getPrompt(skillId);
        if (!args.isEmpty()) {
            prompt = prompt + "\n\nЗапрос пользователя: " + args;
        }
        return prompt;
    }

    /**
     * The legacy "Контекст" toggle + mode selector only affect cloud providers; CLI agents always
     * get the tree via the ops path. Hide them when a CLI model is selected to avoid confusion.
     */
    private void updateContextControlsVisibility() {
        boolean cloud = !isCliModelSelected();
        if (contextToggle != null) {
            contextToggle.setVisible(cloud);
        }
        if (contextModeSelector != null) {
            contextModeSelector.setVisible(cloud);
        }
    }

    /** Whether the currently selected dropdown model is a local CLI agent. */
    private boolean isCliModelSelected() {
        Object sel = modelSelector == null ? null : modelSelector.getSelectedItem();
        if (!(sel instanceof String)) {
            return false;
        }
        String m = (String) sel;
        return m.startsWith("qwen-cli:") || m.startsWith("gigacode-cli:");
    }

    /**
     * Augments the request for a CLI agent with the jmeter-ops protocol instructions and the current
     * tree (with #ids and a revision hash). The revision is remembered so a later apply can reject
     * stale-id edits made against a tree that has since changed.
     */
    private List<String> withCliOpsContext(List<String> base) {
        List<String> conv = stripCommandTurns(base);
        if (conv.isEmpty()) {
            return conv;
        }
        String[] ctx = currentTreeContext();
        String tree = ctx[0];
        String revision = ctx[1];
        this.lastCliOpsRevision = revision;
        this.lastSentTreeRevision = revision;

        // Prepend protocol to the first turn, append fresh tree context to the last user turn.
        conv.set(0, OpsProtocol.SYSTEM_PROMPT + "\n" + conv.get(0));
        int last = conv.size() - 1;
        conv.set(last, conv.get(last) + "\n\n" + OpsProtocol.buildContextBlock(tree, revision));
        return conv;
    }

    /**
     * Handles a CLI agent reply that carries a {@code jmeter-ops} edit block: shows the textual part,
     * parses/validates the ops, previews them, asks for confirmation, applies on the EDT, and reports
     * the outcome. Read-only {@code get_element} batches are applied without a confirmation prompt.
     */
    private void handleCliOpsResponse(String response, boolean narrativeAlreadyShown) {
        // Never show the raw ops JSON to the user. The narrative is the text minus the fenced block.
        String narrative = response.replaceAll(
                "(?s)```[ \\t]*[Jj][Mm][Ee][Tt][Ee][Rr]-[Oo][Pp][Ss].*?```", "").trim();
        if (narrativeAlreadyShown) {
            // The block streamed live — wipe everything streamed this turn and re-render clean text.
            clearStreamedRegion();
        }
        if (!narrative.isEmpty()) {
            processAiResponse(narrative);
        }

        String payload = FencedOpsExtractor.extract(response);
        List<PlanOp> ops;
        try {
            ops = PlanOpsParser.parse(payload);
            if (ops.isEmpty()) {
                // Agent emitted an empty block (no changes) — purely informational, nothing to do.
                return;
            }
            PlanOpsValidator.validate(ops);
        } catch (OpsException e) {
            StringBuilder msg = new StringBuilder("Не удалось применить операции: " + e.getMessage());
            for (String p : e.problems()) {
                msg.append("\n  • ").append(p);
            }
            processAiResponse(msg.toString());
            return;
        }

        boolean mutating = ops.stream().anyMatch(o -> o.op() != null && !o.op().isReadOnly());
        if (mutating) {
            String preview = OpsPreviewRenderer.render(ops);
            int choice = JOptionPane.showConfirmDialog(this, preview,
                    "Применить изменения агента?", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                processAiResponse("Изменения отклонены пользователем.");
                return;
            }
        }

        OpsApplyResult result = OpsApplier.apply(ops, lastCliOpsRevision);
        if (result.isMutated()) {
            lastCommandType = LastCommandType.CLI_OPS;
        }
        processAiResponse(result.summary());
    }

    private List<String> buildConversationForAi() {
        List<String> requestConversation = new ArrayList<>(conversationHistory);
        if (requestConversation.isEmpty()) {
            return requestConversation;
        }

        // Prepend system persona on first turn only
        if (requestConversation.size() == 1) {
            String systemInstruction = "Ты ассистент по Apache JMeter. Отвечай на русском языке.\n" +
                    "Перед каждым ответом мысленно разберись в вопросе и контексте.\n" +
                    "Выдавай только финальный ответ — без заголовков шагов и без пересказа вопроса.\n" +
                    "";
            requestConversation.set(0, systemInstruction + "\nВопрос: " + requestConversation.get(0));
        }

        // CLI providers receive the tree context (with #ids + revision) via withCliOpsContext /
        // session turns. Skip this legacy block for them — otherwise the tree is sent twice, with two
        // different id numberings (this block serializes from getRoot(), the ops path from planRoot()).
        if (isCliModelSelected()) {
            return requestConversation;
        }

        if (!isChatContextEnabled()) {
            return requestConversation;
        }

        int lastIndex = requestConversation.size() - 1;
        String lastMessage = requestConversation.get(lastIndex);
        if (lastMessage == null || lastMessage.trim().isEmpty() || lastMessage.trim().startsWith("@")) {
            return requestConversation;
        }

        String context = buildOptionalChatContext();
        if (context.isEmpty()) {
            return requestConversation;
        }

        requestConversation.set(lastIndex,
                lastMessage + "\n\n[Контекст JMeter]\n" + context + "\n[/Контекст JMeter]");
        return requestConversation;
    }

    private boolean isChatContextEnabled() {
        if (contextToggle != null) {
            return contextToggle.isSelected();
        }
        return isContextEnabledByProperty();
    }

    private boolean isContextEnabledByProperty() {
        String enabled = AiConfig.getProperty("gigameter.chat.context.enabled", "false");
        return "true".equalsIgnoreCase(enabled) || "1".equals(enabled) || "yes".equalsIgnoreCase(enabled);
    }

    private String buildOptionalChatContext() {
        String mode = getContextMode();
        StringBuilder context = new StringBuilder();

        boolean includeSelected = "selected".equals(mode) || "selected+plan".equals(mode) || "all".equals(mode);
        boolean includePlan = "plan".equals(mode) || "selected+plan".equals(mode) || "all".equals(mode);

        if (includeSelected) {
            String selected = getSelectedElementContextSummary();
            if (selected != null && !selected.trim().isEmpty()) {
                context.append("Выбранный элемент:\n").append(selected).append("\n\n");
            }
        }
        if (includePlan) {
            String planSummary = getTestPlanContextSummary();
            if (!planSummary.isEmpty()) {
                context.append("Тест-план (JSON):\n").append(planSummary);
            }
        }

        return context.toString().trim();
    }

    private String getContextMode() {
        if (contextModeSelector != null) {
            Object selected = contextModeSelector.getSelectedItem();
            if (selected != null) {
                return selected.toString().trim().toLowerCase();
            }
        }
        return getContextModeByProperty();
    }

    private String getContextModeByProperty() {
        String mode = AiConfig.getProperty("gigameter.chat.context.mode", "selected");
        if (mode == null) {
            return "selected";
        }
        String normalized = mode.trim().toLowerCase();
        if ("plan".equals(normalized) || "selected+plan".equals(normalized) || "all".equals(normalized)) {
            return normalized;
        }
        return "selected";
    }

    private String getTestPlanContextSummary() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null || guiPackage.getTreeModel() == null) {
                return "";
            }
            Object rootObj = guiPackage.getTreeModel().getRoot();
            if (!(rootObj instanceof JMeterTreeNode)) {
                return "";
            }
            JMeterTreeNode root = (JMeterTreeNode) rootObj;
            JMeterPlanSerializer.SerializedPlan plan = JMeterPlanSerializer.serialize(root, 150, 20);
            return plan.toReadableTree();
        } catch (Exception e) {
            log.debug("Failed to build test plan context summary", e);
            return "";
        }
    }

    private String getSelectedElementContextSummary() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null || guiPackage.getTreeListener() == null) {
                return "";
            }

            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null || currentNode.getTestElement() == null) {
                return "";
            }

            TestElement element = currentNode.getTestElement();
            if (element.getClass().getSimpleName().contains("JSR223")) {
                String treePath = buildTreePath(currentNode);
                return buildSelectedElementContext(
                        element.getClass().getSimpleName(),
                        currentNode.getName(),
                        treePath,
                        element.getPropertyAsString("scriptLanguage"),
                        element.getPropertyAsString("script"),
                        4000);
            }

            StringBuilder out = new StringBuilder();
            out.append("- Имя: ").append(currentNode.getName()).append("\n");
            out.append("- Тип: ").append(element.getClass().getSimpleName()).append("\n");
            out.append("- Дочерних: ").append(currentNode.getChildCount()).append("\n");
            if (currentNode.getParent() instanceof JMeterTreeNode) {
                JMeterTreeNode parent = (JMeterTreeNode) currentNode.getParent();
                out.append("- Родитель: ").append(parent.getName());
            }
            return out.toString();
        } catch (Throwable t) {
            log.debug("Failed to build selected element summary", t);
            return "";
        }
    }

    private String buildTreePath(JMeterTreeNode node) {
        if (node == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        JMeterTreeNode current = node;
        while (current != null) {
            parts.add(0, current.getName());
            javax.swing.tree.TreeNode parent = current.getParent();
            current = (parent instanceof JMeterTreeNode) ? (JMeterTreeNode) parent : null;
        }
        return String.join(" > ", parts);
    }

    /** Test seam: assemble the two-layer plan context from a prebuilt plan + selected ids. */
    static String buildPlanContextForTest(SerializedPlan plan, java.util.List<Integer> selectedIds,
                                          int threshold, int maxChars) {
        return PlanContextBuilder.build(plan, selectedIds, threshold, maxChars);
    }

    static String buildSelectedElementContextForTest(
            String elementType,
            String elementName,
            String treePath,
            String scriptLanguage,
            String scriptBody,
            int maxChars) {
        return buildSelectedElementContext(elementType, elementName, treePath, scriptLanguage, scriptBody, maxChars);
    }

    private static String buildSelectedElementContext(
            String elementType,
            String elementName,
            String treePath,
            String scriptLanguage,
            String scriptBody,
            int maxChars) {
        String trimmedScript = truncate(scriptBody, maxChars);
        return "- Имя: " + elementName + "\n" +
                "- Тип: " + elementType + "\n" +
                "- Путь: " + treePath + "\n" +
                "- Язык скрипта: " + scriptLanguage + "\n" +
                "- Скрипт:\n" + trimmedScript;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(truncated)";
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
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, getInfoTextColor(),
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
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, getInfoTextColor(),
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
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, getInfoTextColor(),
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

    private Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private Color getPrimaryTextColor() {
        return uiColor("TextPane.foreground", Color.BLACK);
    }

    private Color getSecondaryTextColor() {
        return uiColor("Label.disabledForeground", Color.GRAY);
    }

    private Color getInfoTextColor() {
        Color info = UIManager.getColor("Link.foreground");
        if (info != null) {
            return info;
        }
        info = UIManager.getColor("Component.linkColor");
        if (info != null) {
            return info;
        }
        return uiColor("TextPane.foreground", new Color(0, 51, 102));
    }

    private Color getBorderColor() {
        return uiColor("Separator.foreground", Color.LIGHT_GRAY);
    }

    private AiService getServiceForSelectedModel() {
        String selectedModel = (String) modelSelector.getSelectedItem();
        if (selectedModel == null) {
            return qwenCodeCliService;
        }
        if (selectedModel.startsWith("giga:")) {
            return gigaChatService;
        }
        if (selectedModel.startsWith("deepseek:")) {
            return deepSeekService;
        }
        if (selectedModel.startsWith("qwen-cli:")) {
            return qwenCodeCliService;
        }
        if (selectedModel.startsWith("gigacode-cli:")) {
            return gigaCodeCliService;
        }
        return qwenCodeCliService;
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
            if (chatArea != null) {
                chatArea.setForeground(getPrimaryTextColor());
            }
            if (messageField != null) {
                messageField.setForeground(getPrimaryTextColor());
            }
            if (navigationPanel != null) {
                navigationPanel.setBackground(uiColor("Panel.background", new Color(245, 245, 250)));
            }
        }
    }
}


