package org.gigameter.jmeter.ai.plan;

final class PlanCommandParser {
    static final String USAGE_MESSAGE = "Usage: @plan <backend scenario> OR @plan apply OR @plan analyze. Example: @plan Login, get token, list products, add to cart, checkout. 100 users, ramp-up 60s, duration 10m.";

    PlanCommandRequest parse(String message) {
        if (message == null) {
            return PlanCommandRequest.invalid();
        }

        String scenario = message.trim().replaceFirst("^@plan\\s*", "").trim();
        if (scenario.isEmpty()) {
            return PlanCommandRequest.invalid();
        }
        if ("apply".equalsIgnoreCase(scenario)) {
            return PlanCommandRequest.apply();
        }
        if ("analyze".equalsIgnoreCase(scenario)) {
            return PlanCommandRequest.analyze();
        }
        return PlanCommandRequest.generate(scenario);
    }
}
