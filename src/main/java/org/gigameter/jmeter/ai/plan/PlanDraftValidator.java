package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;

final class PlanDraftValidator {
    void validate(JsonNode root) {
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
}
