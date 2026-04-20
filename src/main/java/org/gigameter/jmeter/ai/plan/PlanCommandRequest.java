package org.gigameter.jmeter.ai.plan;

final class PlanCommandRequest {
    enum Mode {
        INVALID,
        GENERATE,
        APPLY,
        ANALYZE
    }

    private final Mode mode;
    private final String scenario;

    private PlanCommandRequest(Mode mode, String scenario) {
        this.mode = mode;
        this.scenario = scenario == null ? "" : scenario;
    }

    static PlanCommandRequest invalid() {
        return new PlanCommandRequest(Mode.INVALID, "");
    }

    static PlanCommandRequest apply() {
        return new PlanCommandRequest(Mode.APPLY, "");
    }

    static PlanCommandRequest analyze() {
        return new PlanCommandRequest(Mode.ANALYZE, "");
    }

    static PlanCommandRequest generate(String scenario) {
        return new PlanCommandRequest(Mode.GENERATE, scenario);
    }

    Mode getMode() {
        return mode;
    }

    String getScenario() {
        return scenario;
    }
}
