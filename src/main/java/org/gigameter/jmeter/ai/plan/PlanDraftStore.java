package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stores latest generated plan draft for @plan apply.
 */
public final class PlanDraftStore {
    private static JsonNode latestDraft;
    private static String latestScenario;

    private PlanDraftStore() {
    }

    public static synchronized void save(JsonNode draft, String scenario) {
        latestDraft = draft;
        latestScenario = scenario;
    }

    public static synchronized JsonNode getLatestDraft() {
        return latestDraft;
    }

    public static synchronized String getLatestScenario() {
        return latestScenario;
    }
}
