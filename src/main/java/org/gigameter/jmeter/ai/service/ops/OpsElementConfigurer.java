package org.gigameter.jmeter.ai.service.ops;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies {@link PlanOp} props to a JMeter element, including <b>collection</b> properties that a
 * plain {@code setProperty(key,value)} cannot build. The agent uses conventional virtual keys; this
 * class translates them into the typed JMeter structures (via reflection, so it stays version- and
 * classpath-tolerant — including BlazeMeter/jmeter-plugins elements that may be absent).
 *
 * <p>Virtual keys (documented to the agent in {@link OpsProtocol}):
 * <ul>
 *   <li>HTTP Sampler: {@code body} (raw request body), {@code param.<name>} (form parameter)</li>
 *   <li>Header Manager: {@code header.<name>} (request header)</li>
 *   <li>Response Assertion: {@code assert.field} (code|data|message|headers),
 *       {@code assert.type} (contains|matches|equals|substring), {@code assert.patterns}
 *       (comma-separated), or the shortcut {@code assert.code}</li>
 * </ul>
 * Any other key is set verbatim as a scalar property.
 */
public final class OpsElementConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OpsElementConfigurer.class);

    private OpsElementConfigurer() {
    }

    /** Applies all {@code props} to {@code el}, handling virtual collection keys. */
    public static void configure(TestElement el, Map<String, String> props) {
        if (el == null || props == null || props.isEmpty()) {
            return;
        }
        Map<String, String> assertKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null) {
                continue;
            }
            if ("body".equalsIgnoreCase(key)) {
                setRawBody(el, value);
            } else if (key.startsWith("param.")) {
                addFormParam(el, key.substring("param.".length()), value);
            } else if (key.startsWith("header.")) {
                addHeader(el, key.substring("header.".length()), value);
            } else if (key.startsWith("assert.")) {
                assertKeys.put(key.substring("assert.".length()).toLowerCase(), value);
            } else if ("utg.schedule".equalsIgnoreCase(key)) {
                // Ultimate Thread Group load steps: rows "start,delay,startup,hold,shutdown" separated by ';'
                el.setProperty(buildSchedule("ultimatethreadgroupdata", value));
            } else if ("tst.schedule".equalsIgnoreCase(key)) {
                // Throughput Shaping Timer rows: "startRPS,endRPS,durationSec" separated by ';'
                el.setProperty(buildSchedule("load_profile", value));
            } else {
                el.setProperty(key, value);
            }
        }
        if (!assertKeys.isEmpty()) {
            configureAssertion(el, assertKeys);
        }
    }

    // ---- HTTP raw body ------------------------------------------------------

    private static void setRawBody(TestElement sampler, String bodyText) {
        sampler.setProperty("HTTPSampler.postBodyRaw", "true");
        try {
            invoke(sampler, "setPostBodyRaw", new Class<?>[] {boolean.class}, true);
            Object args = invoke(sampler, "getArguments", null);
            if (args != null) {
                tryInvoke(args, "removeAllArguments", null);
            }
            invoke(sampler, "addNonEncodedArgument",
                    new Class<?>[] {String.class, String.class, String.class}, "", bodyText, "");
            return;
        } catch (Exception e) {
            log.debug("setPostBodyRaw typed API failed, trying Arguments fallback: {}", e.getMessage());
        }
        try {
            Object args = invoke(sampler, "getArguments", null);
            if (args != null) {
                tryInvoke(args, "removeAllArguments", null);
                args.getClass().getMethod("addArgument", String.class, String.class).invoke(args, "", bodyText);
            }
        } catch (Exception e) {
            log.debug("Raw body Arguments fallback failed: {}", e.getMessage());
        }
    }

    // ---- HTTP form parameter ------------------------------------------------

    private static void addFormParam(TestElement sampler, String name, String value) {
        try {
            Object args = invoke(sampler, "getArguments", null);
            if (args != null) {
                args.getClass().getMethod("addArgument", String.class, String.class)
                        .invoke(args, name, value);
            }
        } catch (Exception e) {
            log.debug("Failed to add form param {}: {}", name, e.getMessage());
        }
    }

    // ---- Header Manager header ---------------------------------------------

    private static void addHeader(TestElement headerManager, String name, String value) {
        try {
            Class<?> headerClass = Class.forName("org.apache.jmeter.protocol.http.control.Header");
            tryInvoke(headerManager, "removeHeaderNamed", new Class<?>[] {String.class}, name);
            Constructor<?> ctor = headerClass.getConstructor(String.class, String.class);
            Object header = ctor.newInstance(name, value);
            headerManager.getClass().getMethod("add", headerClass).invoke(headerManager, header);
        } catch (Exception e) {
            log.debug("Failed to add header {} (is this a Header Manager?): {}", name, e.getMessage());
        }
    }

    // ---- Response Assertion -------------------------------------------------

    private static void configureAssertion(TestElement assertion, Map<String, String> a) {
        try {
            String field = a.getOrDefault("field", a.containsKey("code") ? "code" : "data");
            applyAssertField(assertion, field);

            String type = a.getOrDefault("type", a.containsKey("code") ? "equals" : "contains");
            applyAssertType(assertion, type);

            String patterns = a.containsKey("code") ? a.get("code") : a.get("patterns");
            if (patterns == null) {
                patterns = a.get("pattern");
            }
            if (patterns != null) {
                for (String p : patterns.split(",")) {
                    String pat = p.trim();
                    if (!pat.isEmpty()) {
                        tryInvoke(assertion, "addTestString", new Class<?>[] {String.class}, pat);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Assertion config failed: {}", e.getMessage());
        }
    }

    private static void applyAssertField(TestElement assertion, String field) {
        String f = field == null ? "" : field.toLowerCase();
        String method;
        if (f.contains("code")) {
            method = "setTestFieldResponseCode";
        } else if (f.contains("message")) {
            method = "setTestFieldResponseMessage";
        } else if (f.contains("header")) {
            method = "setTestFieldResponseHeaders";
        } else {
            method = "setTestFieldResponseData";
        }
        tryInvoke(assertion, method, null);
    }

    private static void applyAssertType(TestElement assertion, String type) {
        String t = type == null ? "" : type.toLowerCase();
        String method;
        if (t.contains("match")) {
            method = "setToMatchType";
        } else if (t.contains("equal")) {
            method = "setToEqualsType";
        } else if (t.contains("substring")) {
            method = "setToSubstringType";
        } else {
            method = "setToContainsType";
        }
        tryInvoke(assertion, method, null);
    }

    // ---- jmeter-plugins load schedules (Ultimate TG, Throughput Shaping Timer) --------------

    /**
     * Builds a {@link CollectionProperty} schedule (rows of cells) used by jmeter-plugins load
     * elements. Input: rows separated by {@code ;}, cells within a row by {@code ,}. Empty cells and
     * rows are skipped. Works without referencing the plugin classes — it's a generic table property.
     */
    private static CollectionProperty buildSchedule(String propertyName, String spec) {
        CollectionProperty rows = new CollectionProperty();
        rows.setName(propertyName);
        if (spec != null) {
            for (String rowStr : spec.split(";")) {
                if (rowStr.trim().isEmpty()) {
                    continue;
                }
                CollectionProperty row = new CollectionProperty();
                row.setName("");
                for (String cell : rowStr.split(",")) {
                    row.addProperty(new StringProperty("", cell.trim()));
                }
                rows.addProperty(row);
            }
        }
        return rows;
    }

    // ---- reflection helpers -------------------------------------------------

    private static Object invoke(Object target, String method, Class<?>[] sig, Object... args)
            throws Exception {
        Method m = sig == null ? target.getClass().getMethod(method) : target.getClass().getMethod(method, sig);
        return m.invoke(target, args);
    }

    private static void tryInvoke(Object target, String method, Class<?>[] sig, Object... args) {
        try {
            invoke(target, method, sig, args);
        } catch (Exception e) {
            log.debug("Optional call {} skipped: {}", method, e.getMessage());
        }
    }
}
