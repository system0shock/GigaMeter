package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.gigameter.jmeter.ai.service.AiService;
import org.gigameter.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreePath;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * Handles @plan command and returns preview of generated backend test plan.
 */
public class PlanCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(PlanCommandHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiService aiService;

    public PlanCommandHandler(AiService aiService) {
        this.aiService = aiService;
    }

    public String processPlanCommand(String message) {
        if (message == null) {
            return usageMessage();
        }

        String scenario = message.trim().replaceFirst("^@plan\\s*", "").trim();
        if (scenario.isEmpty()) {
            return usageMessage();
        }
        if ("apply".equalsIgnoreCase(scenario)) {
            return applyLatestPlan();
        }

        try {
            String prompt = buildPrompt(scenario);
            String aiResponse = aiService.generateResponse(Collections.singletonList(prompt));
            JsonNode planJson = extractAndParseJson(aiResponse);
            validate(planJson);
            PlanDraftStore.save(planJson, scenario);
            return buildPreview(planJson, scenario);
        } catch (Exception e) {
            log.error("Failed to build plan preview", e);
            return "Failed to generate structured plan preview. " +
                    "Please provide a more explicit backend scenario with API steps, load profile, and expected status codes.";
        }
    }

    private String usageMessage() {
        return "Usage: @plan <backend scenario> OR @plan apply. Example: @plan Login, get token, list products, add to cart, checkout. 100 users, ramp-up 60s, duration 10m.";
    }

    private String buildPrompt(String scenario) {
        return "You are generating a JMeter backend API test plan draft.\n" +
                "Return ONLY valid JSON. Do not add explanations.\n" +
                "Schema:\n" +
                "{\n" +
                "  \"thread_group\": {\n" +
                "    \"name\": \"string\",\n" +
                "    \"users\": number,\n" +
                "    \"ramp_up_seconds\": number,\n" +
                "    \"duration_seconds\": number\n" +
                "  },\n" +
                "  \"defaults\": {\n" +
                "    \"base_url\": \"string\",\n" +
                "    \"think_time_ms\": number,\n" +
                "    \"csv\": {\n" +
                "      \"file\": \"data/users.csv\",\n" +
                "      \"variables\": [\"username\", \"password\"],\n" +
                "      \"delimiter\": \",\",\n" +
                "      \"recycle\": true,\n" +
                "      \"stop_thread_on_eof\": false,\n" +
                "      \"ignore_first_line\": true\n" +
                "    }\n" +
                "  },\n" +
                "  \"steps\": [\n" +
                "    {\n" +
                "      \"name\": \"string\",\n" +
                "      \"sampler_type\": \"http|jsr223\",\n" +
                "      \"method\": \"GET|POST|PUT|PATCH|DELETE\",\n" +
                "      \"path\": \"/path\",\n" +
                "      \"headers\": {\"Header-Name\": \"value\"},\n" +
                "      \"query\": {\"key\": \"value\"},\n" +
                "      \"body\": {\"json\": \"object or string\"},\n" +
                "      \"script_language\": \"groovy\",\n" +
                "      \"script\": \"vars.put(\\\"token\\\", \\\"abc\\\")\",\n" +
                "      \"pre_processors\": [{\"type\":\"jsr223\",\"name\":\"Prepare Vars\",\"script_language\":\"groovy\",\"script\":\"vars.put(\\\"ts\\\", String.valueOf(System.currentTimeMillis()))\"}],\n" +
                "      \"post_processors\": [{\"type\":\"jsr223\",\"name\":\"Extract Vars\",\"script_language\":\"groovy\",\"script\":\"vars.put(\\\"status\\\", prev.getResponseCode())\"}],\n" +
                "      \"think_time_ms\": number,\n" +
                "      \"extract\": {\"var\": \"token\", \"json_path\": \"$.token\"},\n" +
                "      \"assert\": {\"status_code\": 200}\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "Scenario:\n" + scenario;
    }

    private JsonNode extractAndParseJson(String response) throws Exception {
        if (response == null || response.trim().isEmpty()) {
            throw new IllegalStateException("Empty AI response");
        }

        String normalized = response.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceAll("^```[a-zA-Z]*\\s*", "");
            normalized = normalized.replaceAll("\\s*```$", "");
        }

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("No JSON object found in AI response");
        }

        String json = normalized.substring(start, end + 1);
        return OBJECT_MAPPER.readTree(json);
    }

    private void validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Root is not a JSON object");
        }

        JsonNode threadGroup = root.path("thread_group");
        if (!threadGroup.isObject()) {
            throw new IllegalStateException("thread_group is missing");
        }

        if (!threadGroup.path("users").isNumber()) {
            throw new IllegalStateException("thread_group.users must be numeric");
        }

        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.size() == 0) {
            throw new IllegalStateException("steps must be non-empty array");
        }
    }

    private String buildPreview(JsonNode root, String scenario) {
        StringBuilder out = new StringBuilder();
        out.append("# AI Plan Preview (Draft)\n\n");
        out.append("`Preview only` - no changes were applied to the test plan.\n\n");
        out.append("## Scenario\n");
        out.append(scenario).append("\n\n");

        JsonNode tg = root.path("thread_group");
        out.append("## Thread Group\n");
        out.append("- Name: ").append(textOrDefault(tg.path("name"), "API Users")).append("\n");
        out.append("- Users: ").append(numberOrDefault(tg.path("users"), 1)).append("\n");
        out.append("- Ramp-up (s): ").append(numberOrDefault(tg.path("ramp_up_seconds"), 1)).append("\n");
        out.append("- Duration (s): ").append(numberOrDefault(tg.path("duration_seconds"), 60)).append("\n\n");

        JsonNode defaults = root.path("defaults");
        if (defaults.isObject() && defaults.has("base_url")) {
            out.append("## Defaults\n");
            out.append("- Base URL: ").append(textOrDefault(defaults.path("base_url"), "")).append("\n\n");
        }
        if (defaults.isObject() && defaults.has("think_time_ms")) {
            out.append("- Default think time (ms): ")
                    .append(numberOrDefault(defaults.path("think_time_ms"), 0)).append("\n");
        }
        JsonNode csv = defaults.path("csv");
        if (csv.isObject() && csv.has("file")) {
            out.append("- CSV Data Set: ").append(textOrDefault(csv.path("file"), "")).append("\n");
            if (csv.has("variables")) {
                out.append("  - CSV variables: ").append(joinCsvVariables(csv.path("variables"))).append("\n");
            }
        }
        if (defaults.isObject()) {
            out.append("\n");
        }

        out.append("## Steps\n");
        JsonNode steps = root.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String name = textOrDefault(step.path("name"), "Step " + (i + 1));
            String samplerType = textOrDefault(step.path("sampler_type"), "http").toLowerCase();
            String method = textOrDefault(step.path("method"), "GET");
            String path = textOrDefault(step.path("path"), "/");
            if ("jsr223".equals(samplerType)) {
                out.append(i + 1).append(". ").append(name).append(" - JSR223 ")
                        .append(textOrDefault(step.path("script_language"), "groovy")).append("\n");
            } else {
                out.append(i + 1).append(". ").append(name).append(" - ").append(method).append(" ").append(path).append("\n");
            }

            JsonNode assertion = step.path("assert");
            if (assertion.isObject() && assertion.has("status_code")) {
                out.append("   - Assert status: ").append(numberOrDefault(assertion.path("status_code"), 200)).append("\n");
            }

            JsonNode extract = step.path("extract");
            if (extract.isObject() && extract.has("var") && extract.has("json_path")) {
                out.append("   - Extract: ").append(textOrDefault(extract.path("var"), "var"))
                        .append(" <= ").append(textOrDefault(extract.path("json_path"), "$")).append("\n");
            }

            JsonNode headers = step.path("headers");
            if (headers.isObject()) {
                int headerCount = 0;
                Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
                while (it.hasNext()) {
                    it.next();
                    headerCount++;
                }
                if (headerCount > 0) {
                    out.append("   - Headers: ").append(headerCount).append("\n");
                }
            }

            JsonNode query = step.path("query");
            if (query.isObject() && query.size() > 0) {
                out.append("   - Query params: ").append(query.size()).append("\n");
            }

            JsonNode body = step.path("body");
            if (!body.isMissingNode() && !body.isNull()) {
                out.append("   - Body: yes\n");
            }

            int thinkTime = numberOrDefault(step.path("think_time_ms"), 0);
            if (thinkTime > 0) {
                out.append("   - Think time (ms): ").append(thinkTime).append("\n");
            }

            JsonNode preProcessors = step.path("pre_processors");
            if (preProcessors.isArray() && preProcessors.size() > 0) {
                out.append("   - Pre-processors: ").append(preProcessors.size()).append("\n");
            }
            JsonNode postProcessors = step.path("post_processors");
            if (postProcessors.isArray() && postProcessors.size() > 0) {
                out.append("   - Post-processors: ").append(postProcessors.size()).append("\n");
            }
        }

        out.append("\n## Next Step\n");
        out.append("If preview looks good, run `@plan apply` to build this structure in JMeter.");
        return out.toString();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isValueNode() ? node.asText() : fallback;
    }

    private int numberOrDefault(JsonNode node, int fallback) {
        return node != null && node.isNumber() ? node.asInt() : fallback;
    }

    private String applyLatestPlan() {
        JsonNode draft = PlanDraftStore.getLatestDraft();
        if (draft == null || !draft.isObject()) {
            return "No plan draft found. Run `@plan <scenario>` first, review preview, then run `@plan apply`.";
        }

        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return "JMeter GUI is not available, cannot apply plan.";
        }

        if (!JMeterElementManager.ensureTestPlanExists()) {
            return "Failed to ensure a test plan exists.";
        }
        if (!JMeterElementManager.selectTestPlanNode()) {
            return "Failed to select Test Plan node.";
        }

        JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        if (root == null) {
            return "Test plan root node is unavailable.";
        }

        int createdCount = 0;
        int skippedCount = 0;

        JsonNode threadGroup = draft.path("thread_group");
        String tgName = textOrDefault(threadGroup.path("name"), "API Users");
        if (!JMeterElementManager.addElement("threadgroup", tgName)) {
            return "Failed to create Thread Group from plan draft.";
        }
        createdCount++;

        JMeterTreeNode tgNode = findLastChildByName(root, tgName);
        if (tgNode == null) {
            tgNode = getLastChild(root);
        }
        if (tgNode == null) {
            return "Failed to find created Thread Group node.";
        }
        PlanApplyUndoStore.save(tgNode);
        configureThreadGroup(tgNode, threadGroup);

        JsonNode defaults = draft.path("defaults");
        String baseUrl = defaults.path("base_url").asText("");
        int defaultThinkTimeMs = numberOrDefault(defaults.path("think_time_ms"), 0);
        if (!baseUrl.isEmpty()) {
            selectNode(tgNode);
            if (JMeterElementManager.addElement("httpdefaults", "HTTP Defaults (AI Plan)")) {
                createdCount++;
            }
        }
        JsonNode csv = defaults.path("csv");
        if (csv.isObject() && csv.has("file")) {
            selectNode(tgNode);
            if (JMeterElementManager.addElement("csvdataset", "CSV Data Set (AI Plan)")) {
                createdCount++;
                JMeterTreeNode csvNode = getLastChild(tgNode);
                configureCsvDataSet(csvNode, csv);
            }
        }

        JsonNode steps = draft.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String stepName = textOrDefault(step.path("name"), "Step " + (i + 1));
            String samplerType = textOrDefault(step.path("sampler_type"), "http").toLowerCase();
            String samplerElementType = "jsr223".equals(samplerType) ? "jsr223sampler" : "httpsampler";

            selectNode(tgNode);
            if (!JMeterElementManager.addElement(samplerElementType, stepName)) {
                skippedCount++;
                continue;
            }
            createdCount++;

            JMeterTreeNode samplerNode = findLastChildByName(tgNode, stepName);
            if (samplerNode == null) {
                samplerNode = getLastChild(tgNode);
            }
            if (samplerNode == null) {
                skippedCount++;
                continue;
            }

            if ("jsr223".equals(samplerType)) {
                configureJsr223ScriptElement(
                        samplerNode,
                        textOrDefault(step.path("script_language"), "groovy"),
                        textOrDefault(step.path("script"), ""));
            } else {
                configureHttpSampler(samplerNode, step, baseUrl);
            }

            JsonNode headers = step.path("headers");
            if (headers.isObject() && headers.size() > 0) {
                selectNode(samplerNode);
                if (JMeterElementManager.addElement("headermanager", "Headers - " + stepName)) {
                    createdCount++;
                    JMeterTreeNode headerNode = getLastChild(samplerNode);
                    configureHeaderManager(headerNode, headers);
                }
            }

            JsonNode assertion = step.path("assert");
            if (assertion.isObject() && assertion.has("status_code")) {
                selectNode(samplerNode);
                if (JMeterElementManager.addElement("responseassert", "Assert Status - " + stepName)) {
                    createdCount++;
                    JMeterTreeNode assertNode = getLastChild(samplerNode);
                    configureResponseAssertion(assertNode, assertion.path("status_code").asInt(200));
                }
            }

            JsonNode extract = step.path("extract");
            if (extract.isObject() && extract.has("var") && extract.has("json_path")) {
                selectNode(samplerNode);
                if (JMeterElementManager.addElement("jsonpathextractor", "Extract - " + stepName)) {
                    createdCount++;
                    JMeterTreeNode extractorNode = getLastChild(samplerNode);
                    configureJsonExtractor(extractorNode,
                            textOrDefault(extract.path("var"), "var"),
                            textOrDefault(extract.path("json_path"), "$"));
                }
            }

            int stepThinkTime = numberOrDefault(step.path("think_time_ms"), 0);
            int effectiveThinkTime = stepThinkTime > 0 ? stepThinkTime : defaultThinkTimeMs;
            if (effectiveThinkTime > 0) {
                selectNode(samplerNode);
                if (JMeterElementManager.addElement("constanttimer", "Think Time - " + stepName)) {
                    createdCount++;
                    JMeterTreeNode timerNode = getLastChild(samplerNode);
                    configureConstantTimer(timerNode, effectiveThinkTime);
                }
            }

            JsonNode preProcessors = step.path("pre_processors");
            if (preProcessors.isArray()) {
                createdCount += applyJsr223Processors(samplerNode, preProcessors, true, stepName);
            }

            JsonNode postProcessors = step.path("post_processors");
            if (postProcessors.isArray()) {
                createdCount += applyJsr223Processors(samplerNode, postProcessors, false, stepName);
            }
        }

        guiPackage.getTreeModel().nodeStructureChanged(root);
        guiPackage.getMainFrame().repaint();

        return "Applied AI plan draft.\n" +
                "- Created elements: " + createdCount + "\n" +
                "- Skipped steps: " + skippedCount + "\n" +
                "- Scope: backend HTTP structure, headers/query/body mapping, status assertions, JSON extractors, timers, CSV data set.";
    }

    public static String undoLastAppliedPlan() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return "JMeter GUI is not available, cannot rollback.";
        }

        JMeterTreeNode appliedThreadGroup = PlanApplyUndoStore.getLastAppliedThreadGroup();
        if (appliedThreadGroup == null) {
            return "Nothing to rollback for @plan apply.";
        }

        try {
            if (appliedThreadGroup.getParent() != null) {
                guiPackage.getTreeModel().removeNodeFromParent(appliedThreadGroup);
                guiPackage.getMainFrame().repaint();
                PlanApplyUndoStore.clear();
                return "Rollback completed: last AI-generated Thread Group was removed.";
            }
            PlanApplyUndoStore.clear();
            return "Nothing to rollback for @plan apply.";
        } catch (Exception e) {
            return "Rollback failed: " + e.getMessage();
        }
    }

    private void selectNode(JMeterTreeNode node) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null && node != null) {
            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(node.getPath()));
        }
    }

    private JMeterTreeNode getLastChild(JMeterTreeNode parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }
        return (JMeterTreeNode) parent.getChildAt(parent.getChildCount() - 1);
    }

    private JMeterTreeNode findLastChildByName(JMeterTreeNode parent, String name) {
        if (parent == null || name == null) {
            return null;
        }
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            if (name.equals(child.getName())) {
                return child;
            }
        }
        return null;
    }

    private void configureThreadGroup(JMeterTreeNode node, JsonNode tg) {
        if (node == null || !(node.getTestElement() instanceof ThreadGroup)) {
            return;
        }
        ThreadGroup threadGroup = (ThreadGroup) node.getTestElement();
        int users = numberOrDefault(tg.path("users"), 1);
        int rampUp = numberOrDefault(tg.path("ramp_up_seconds"), 1);
        int duration = numberOrDefault(tg.path("duration_seconds"), 60);

        threadGroup.setNumThreads(Math.max(users, 1));
        threadGroup.setRampUp(Math.max(rampUp, 1));
        threadGroup.setProperty("ThreadGroup.scheduler", "true");
        threadGroup.setProperty("ThreadGroup.duration", String.valueOf(Math.max(duration, 1)));
    }

    private void configureHttpSampler(JMeterTreeNode node, JsonNode step, String baseUrl) {
        if (node == null || node.getTestElement() == null) {
            return;
        }
        TestElement sampler = node.getTestElement();
        String className = sampler.getClass().getName();
        if (!className.contains("HTTPSampler")) {
            return;
        }

        String method = textOrDefault(step.path("method"), "GET").toUpperCase();
        String rawPath = textOrDefault(step.path("path"), "/");
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        path = appendQuery(path, step.path("query"));

        sampler.setProperty("HTTPSampler.method", method);
        sampler.setProperty("HTTPSampler.path", path);

        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            try {
                URI uri = URI.create(baseUrl.trim());
                if (uri.getScheme() != null) {
                    sampler.setProperty("HTTPSampler.protocol", uri.getScheme());
                }
                if (uri.getHost() != null) {
                    sampler.setProperty("HTTPSampler.domain", uri.getHost());
                }
                if (uri.getPort() > 0) {
                    sampler.setProperty("HTTPSampler.port", String.valueOf(uri.getPort()));
                }
            } catch (Exception e) {
                log.warn("Invalid base_url in plan draft: {}", baseUrl);
            }
        }

        JsonNode body = step.path("body");
        if (!body.isMissingNode() && !body.isNull()) {
            String bodyText = body.isTextual() ? body.asText() : body.toString();
            if (!bodyText.trim().isEmpty() && !"GET".equals(method)) {
                setRawBodyOnSampler(sampler, bodyText);
            }
        }
    }

    private String appendQuery(String path, JsonNode queryNode) {
        if (!queryNode.isObject() || queryNode.size() == 0) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        sb.append(path.contains("?") ? "&" : "?");
        Iterator<Map.Entry<String, JsonNode>> fields = queryNode.fields();
        boolean first = true;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (!first) {
                sb.append("&");
            }
            first = false;
            String key = URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8);
            String val = URLEncoder.encode(f.getValue().asText(""), StandardCharsets.UTF_8);
            sb.append(key).append("=").append(val);
        }
        return sb.toString();
    }

    private void configureResponseAssertion(JMeterTreeNode node, int statusCode) {
        if (node == null || node.getTestElement() == null) {
            return;
        }
        TestElement assertion = node.getTestElement();
        assertion.setProperty("Assertion.test_field", "Assertion.response_code");
        assertion.setProperty("Assertion.test_type", "16");
        assertion.setProperty("Assertion.test_strings", String.valueOf(statusCode));
    }

    private void configureJsonExtractor(JMeterTreeNode node, String varName, String jsonPath) {
        if (node == null || node.getTestElement() == null) {
            return;
        }
        node.getTestElement().setProperty("JSONPostProcessor.referenceNames", varName);
        node.getTestElement().setProperty("JSONPostProcessor.jsonPathExprs", jsonPath);
        node.getTestElement().setProperty("JSONPostProcessor.match_numbers", "1");
        node.getTestElement().setProperty("JSONPostProcessor.defaultValues", "NOT_FOUND");
    }

    private String joinCsvVariables(JsonNode variablesNode) {
        if (variablesNode == null || variablesNode.isMissingNode() || variablesNode.isNull()) {
            return "";
        }
        if (variablesNode.isTextual()) {
            return variablesNode.asText();
        }
        if (!variablesNode.isArray()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < variablesNode.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(variablesNode.get(i).asText());
        }
        return sb.toString();
    }

    private void configureCsvDataSet(JMeterTreeNode node, JsonNode csv) {
        if (node == null || node.getTestElement() == null || csv == null || !csv.isObject()) {
            return;
        }

        TestElement csvElement = node.getTestElement();
        csvElement.setProperty("filename", textOrDefault(csv.path("file"), ""));
        csvElement.setProperty("delimiter", textOrDefault(csv.path("delimiter"), ","));
        csvElement.setProperty("variableNames", joinCsvVariables(csv.path("variables")));
        csvElement.setProperty("recycle", String.valueOf(!csv.has("recycle") || csv.path("recycle").asBoolean(true)));
        csvElement.setProperty("stopThread", String.valueOf(csv.path("stop_thread_on_eof").asBoolean(false)));
        csvElement.setProperty("ignoreFirstLine", String.valueOf(csv.path("ignore_first_line").asBoolean(false)));
        csvElement.setProperty("quotedData", String.valueOf(csv.path("quoted_data").asBoolean(false)));
        csvElement.setProperty("shareMode", textOrDefault(csv.path("share_mode"), "shareMode.all"));
    }

    private void configureHeaderManager(JMeterTreeNode node, JsonNode headersNode) {
        if (node == null || node.getTestElement() == null || headersNode == null || !headersNode.isObject()) {
            return;
        }

        Object headerManager = node.getTestElement();
        try {
            Class<?> headerClass = Class.forName("org.apache.jmeter.protocol.http.control.Header");
            Method removeHeaderNamed = headerManager.getClass().getMethod("removeHeaderNamed", String.class);
            Method addMethod = headerManager.getClass().getMethod("add", headerClass);
            Constructor<?> headerCtor = headerClass.getConstructor(String.class, String.class);

            Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                removeHeaderNamed.invoke(headerManager, f.getKey());
                Object header = headerCtor.newInstance(f.getKey(), f.getValue().asText(""));
                addMethod.invoke(headerManager, header);
            }
        } catch (Exception e) {
            log.warn("Failed to map header values to Header Manager. Structure is still created.", e);
        }
    }

    private void configureConstantTimer(JMeterTreeNode node, int delayMs) {
        if (node == null || node.getTestElement() == null) {
            return;
        }
        node.getTestElement().setProperty("ConstantTimer.delay", String.valueOf(Math.max(delayMs, 1)));
    }

    private void setRawBodyOnSampler(TestElement sampler, String bodyText) {
        sampler.setProperty("HTTPSampler.postBodyRaw", "true");

        try {
            Method setPostBodyRawMethod = sampler.getClass().getMethod("setPostBodyRaw", boolean.class);
            setPostBodyRawMethod.invoke(sampler, true);
        } catch (Exception ignored) {
            // Fall back to JMeter property mapping only.
        }

        sampler.setProperty("Argument.value", bodyText);

        try {
            Method getArgumentsMethod = sampler.getClass().getMethod("getArguments");
            Object args = getArgumentsMethod.invoke(sampler);
            if (args != null) {
                try {
                    Method removeAll = args.getClass().getMethod("removeAllArguments");
                    removeAll.invoke(args);
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }

                try {
                    Method addNonEncoded = sampler.getClass().getMethod("addNonEncodedArgument",
                            String.class, String.class, String.class);
                    addNonEncoded.invoke(sampler, "", bodyText, "");
                    return;
                } catch (Exception ignored) {
                    // Fall through to Arguments.addArgument.
                }

                try {
                    Method addArgument = args.getClass().getMethod("addArgument", String.class, String.class);
                    addArgument.invoke(args, "", bodyText);
                } catch (Exception ignored) {
                    // Fallback already set via property.
                }
            }
        } catch (Exception ignored) {
            // Fallback already set via property.
        }
    }

    private void configureJsr223ScriptElement(JMeterTreeNode node, String language, String script) {
        if (node == null || node.getTestElement() == null) {
            return;
        }

        TestElement element = node.getTestElement();
        element.setProperty("scriptLanguage", language == null || language.trim().isEmpty() ? "groovy" : language);
        element.setProperty("script", script == null ? "" : script);
    }

    private int applyJsr223Processors(JMeterTreeNode samplerNode, JsonNode processors, boolean isPre, String stepName) {
        if (samplerNode == null || processors == null || !processors.isArray()) {
            return 0;
        }

        int created = 0;
        String elementType = isPre ? "jsr223preprocessor" : "jsr223postprocessor";
        String prefix = isPre ? "Pre" : "Post";

        for (int i = 0; i < processors.size(); i++) {
            JsonNode processor = processors.get(i);
            String type = textOrDefault(processor.path("type"), "jsr223").toLowerCase();
            if (!"jsr223".equals(type)) {
                continue;
            }

            String processorName = textOrDefault(processor.path("name"),
                    prefix + " Processor " + (i + 1) + " - " + stepName);
            selectNode(samplerNode);
            if (JMeterElementManager.addElement(elementType, processorName)) {
                created++;
                JMeterTreeNode processorNode = getLastChild(samplerNode);
                configureJsr223ScriptElement(
                        processorNode,
                        textOrDefault(processor.path("script_language"), "groovy"),
                        textOrDefault(processor.path("script"), ""));
            }
        }

        return created;
    }
}
