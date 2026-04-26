package org.gigameter.jmeter.ai.plan;

import com.fasterxml.jackson.databind.JsonNode;

final class PlanDraftValidator {
    void validate(JsonNode root) throws PlanDraftException {
        if (root == null || !root.isObject()) {
            throw PlanDraftException.malformedResponse("Root is not a JSON object", null);
        }

        JsonNode threadGroup = root.path("thread_group");
        if (!threadGroup.isObject()) {
            throw PlanDraftException.malformedResponse("thread_group is missing", null);
        }

        if (!threadGroup.path("users").isNumber()) {
            throw PlanDraftException.malformedResponse("thread_group.users must be numeric", null);
        }

        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.size() == 0) {
            throw PlanDraftException.malformedResponse("steps must be non-empty array", null);
        }
    }
}
