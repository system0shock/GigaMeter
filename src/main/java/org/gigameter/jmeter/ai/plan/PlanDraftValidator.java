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
        requireText(threadGroup, "name", "thread_group.name must be non-empty text");

        if (!threadGroup.path("users").isNumber()) {
            throw PlanDraftException.malformedResponse("thread_group.users must be numeric", null);
        }
        requireNumber(threadGroup, "ramp_up_seconds", "thread_group.ramp_up_seconds must be numeric");
        requireNumber(threadGroup, "duration_seconds", "thread_group.duration_seconds must be numeric");

        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.size() == 0) {
            throw PlanDraftException.malformedResponse("steps must be non-empty array", null);
        }
        for (int i = 0; i < steps.size(); i++) {
            validateStep(steps.get(i), i);
        }
    }

    private void validateStep(JsonNode step, int index) throws PlanDraftException {
        if (step == null || !step.isObject()) {
            throw PlanDraftException.malformedResponse("steps[" + index + "] must be an object", null);
        }

        requireText(step, "name", "steps[" + index + "].name must be non-empty text");
        String samplerType = requireText(step, "sampler_type",
                "steps[" + index + "].sampler_type must be non-empty text");

        if ("http".equalsIgnoreCase(samplerType)) {
            requireText(step, "method", "steps[" + index + "].method must be non-empty text for http step");
            requireText(step, "path", "steps[" + index + "].path must be non-empty text for http step");
            return;
        }

        if ("jsr223".equalsIgnoreCase(samplerType)) {
            requireText(step, "script", "steps[" + index + "].script must be non-empty text for jsr223 step");
            return;
        }

        throw PlanDraftException.malformedResponse(
                "steps[" + index + "].sampler_type must be either http or jsr223", null);
    }

    private void requireNumber(JsonNode node, String field, String message) throws PlanDraftException {
        if (!node.path(field).isNumber()) {
            throw PlanDraftException.malformedResponse(message, null);
        }
    }

    private String requireText(JsonNode node, String field, String message) throws PlanDraftException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().trim().isEmpty()) {
            throw PlanDraftException.malformedResponse(message, null);
        }
        return value.asText().trim();
    }
}
