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
                "    \"base_url\": \"string\"\n" +
                "  },\n" +
                "  \"steps\": [\n" +
                "    {\n" +
                "      \"name\": \"string\",\n" +
                "      \"method\": \"GET|POST|PUT|PATCH|DELETE\",\n" +
                "      \"path\": \"/path\",\n" +
                "      \"headers\": {\"Header-Name\": \"value\"},\n" +
                "      \"query\": {\"key\": \"value\"},\n" +
                "      \"body\": {\"json\": \"object or string\"},\n" +
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

        out.append("## Steps\n");
        JsonNode steps = root.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String name = textOrDefault(step.path("name"), "Step " + (i + 1));
            String method = textOrDefault(step.path("method"), "GET");
            String path = textOrDefault(step.path("path"), "/");
            out.append(i + 1).append(". ").append(name).append(" - ").append(method).append(" ").append(path).append("\n");

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
        configureThreadGroup(tgNode, threadGroup);

        JsonNode defaults = draft.path("defaults");
        String baseUrl = defaults.path("base_url").asText("");
        if (!baseUrl.isEmpty()) {
            selectNode(tgNode);
            if (JMeterElementManager.addElement("httpdefaults", "HTTP Defaults (AI Plan)")) {
                createdCount++;
            }
        }

        JsonNode steps = draft.path("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String stepName = textOrDefault(step.path("name"), "Step " + (i + 1));

            selectNode(tgNode);
            if (!JMeterElementManager.addElement("httpsampler", stepName)) {
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
            configureHttpSampler(samplerNode, step, baseUrl);

            JsonNode headers = step.path("headers");
            if (headers.isObject() && headers.size() > 0) {
                selectNode(samplerNode);
                if (JMeterElementManager.addElement("headermanager", "Headers - " + stepName)) {
                    createdCount++;
                    // Header manager structure is created; field-level mapping is intentionally
                    // conservative in this MVP.
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
        }

        guiPackage.getTreeModel().nodeStructureChanged(root);
        guiPackage.getMainFrame().repaint();

        return "Applied AI plan draft.\n" +
                "- Created elements: " + createdCount + "\n" +
                "- Skipped steps: " + skippedCount + "\n" +
                "- Scope: backend HTTP structure, status assertions, JSON extractors.\n" +
                "- Note: request body/query mapping is basic; headers are added as structure in this MVP.";
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
                sampler.setProperty("HTTPSampler.postBodyRaw", "true");
                sampler.setProperty("Argument.value", bodyText);
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
}
