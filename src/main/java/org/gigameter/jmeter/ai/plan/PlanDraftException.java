package org.gigameter.jmeter.ai.plan;

final class PlanDraftException extends Exception {
    enum ErrorCategory {
        EMPTY_RESPONSE,
        MALFORMED_RESPONSE,
        SERVICE_FAILURE
    }

    private final ErrorCategory category;

    private PlanDraftException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    static PlanDraftException emptyResponse() {
        return new PlanDraftException(ErrorCategory.EMPTY_RESPONSE, "AI response was empty", null);
    }

    static PlanDraftException malformedResponse(String message, Throwable cause) {
        return new PlanDraftException(ErrorCategory.MALFORMED_RESPONSE, message, cause);
    }

    static PlanDraftException serviceFailure(Throwable cause) {
        return new PlanDraftException(ErrorCategory.SERVICE_FAILURE, "AI service call failed", cause);
    }

    ErrorCategory getCategory() {
        return category;
    }
}
