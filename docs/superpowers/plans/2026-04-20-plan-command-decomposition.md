# Plan Command Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose the `@plan` command flow so `PlanCommandHandler` becomes a thinner entry point without changing current user-visible behavior.

**Architecture:** Introduce small collaborators for parsing, draft generation, validation, and preview rendering, then move the generate path in `PlanCommandHandler` onto those collaborators while keeping apply/analyze behavior stable for the first iteration.

**Tech Stack:** Java, JUnit 5, Mockito, Maven, Jackson, Apache JMeter APIs

---

### Task 1: Introduce Parsed Command Model

**Files:**
- Create: `src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandRequest.java`
- Create: `src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandParser.java`
- Modify: `src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java`

- [ ] **Step 1: Write failing parser-oriented tests via handler behavior**

Add assertions for blank input, `@plan apply`, `@plan analyze`, and generate scenario routing in `src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java`.

- [ ] **Step 2: Run focused test to verify current coverage baseline**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: current tests pass, new parser-focused assertions fail until parser classes exist.

- [ ] **Step 3: Add request model and parser**

Create `PlanCommandRequest` with mode enum and normalized scenario text, and `PlanCommandParser` that converts raw input into usage/apply/analyze/generate decisions.

- [ ] **Step 4: Wire parser into `PlanCommandHandler`**

Replace inline string parsing in `processPlanCommand()` with the parser while preserving existing usage/apply/analyze behavior.

- [ ] **Step 5: Run focused test to verify it passes**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandRequest.java src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandParser.java src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandHandler.java src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java
git commit -m "refactor: extract @plan command parsing"
```

### Task 2: Extract Draft Generate/Validate/Preview Flow

**Files:**
- Create: `src/main/java/org/gigameter/jmeter/ai/plan/PlanDraftGenerator.java`
- Create: `src/main/java/org/gigameter/jmeter/ai/plan/PlanDraftValidator.java`
- Create: `src/main/java/org/gigameter/jmeter/ai/plan/PlanPreviewRenderer.java`
- Modify: `src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandHandler.java`
- Modify: `src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java`

- [ ] **Step 1: Add failing tests that lock current preview behavior**

Keep existing preview assertions and add at least one assertion that invalid AI output still returns the current failure hint.

- [ ] **Step 2: Run focused test to verify failures**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: FAIL for missing collaborators or changed wiring.

- [ ] **Step 3: Implement focused collaborators**

Move prompt building and JSON extraction into `PlanDraftGenerator`, structural validation into `PlanDraftValidator`, and markdown preview building into `PlanPreviewRenderer`.

- [ ] **Step 4: Refactor handler generate path**

Update `PlanCommandHandler` so the generate branch becomes orchestration only: parse -> generate -> validate -> save -> render.

- [ ] **Step 5: Run focused test to verify it passes**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/plan/PlanDraftGenerator.java src/main/java/org/gigameter/jmeter/ai/plan/PlanDraftValidator.java src/main/java/org/gigameter/jmeter/ai/plan/PlanPreviewRenderer.java src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandHandler.java src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java
git commit -m "refactor: extract @plan draft pipeline"
```

### Task 3: Stabilize Apply State Boundary

**Files:**
- Modify: `src/main/java/org/gigameter/jmeter/ai/plan/PlanApplyUndoStore.java`
- Modify: `src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandHandler.java`
- Modify: `src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java`

- [ ] **Step 1: Add failing tests for apply guard behavior**

Add tests that verify `@plan apply` still refuses to run without a saved draft and rollback still reports empty state correctly.

- [ ] **Step 2: Run focused test to verify baseline**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: baseline failures only if behavior changed during refactor.

- [ ] **Step 3: Tighten storage-only boundary**

Keep `PlanApplyUndoStore` as simple save/get/clear storage, remove any accidental orchestration leakage, and make handler usage explicit.

- [ ] **Step 4: Run focused test to verify it passes**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/plan/PlanApplyUndoStore.java src/main/java/org/gigameter/jmeter/ai/plan/PlanCommandHandler.java src/test/java/org/gigameter/jmeter/ai/plan/PlanCommandHandlerTest.java
git commit -m "refactor: clarify @plan apply undo boundary"
```

### Task 4: Verify and Document

**Files:**
- Modify: `README.md` (only if public behavior text changed)
- Modify: `docs/superpowers/specs/2026-04-20-plan-command-decomposition-design.md`
- Modify: `docs/superpowers/plans/2026-04-20-plan-command-decomposition.md`

- [ ] **Step 1: Run focused verification**

Run: `mvn -Dtest=PlanCommandHandlerTest test`
Expected: PASS

- [ ] **Step 2: Run broader targeted verification**

Run: `mvn -Dtest=PlanCommandHandlerTest,CommandIntellisenseProviderTest test`
Expected: PASS

- [ ] **Step 3: Update docs if behavior wording changed**

Only update user-facing docs if actual command help text or visible behavior changed.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-04-20-plan-command-decomposition-design.md docs/superpowers/plans/2026-04-20-plan-command-decomposition.md
git commit -m "docs: record @plan decomposition iteration"
```

## Self-Review

- Spec coverage: parser, generate pipeline extraction, and apply-state boundary are all covered by tasks above.
- Placeholder scan: no TBD/TODO placeholders remain in task steps.
- Type consistency: the plan uses `PlanCommandRequest`, `PlanCommandParser`, `PlanDraftGenerator`, `PlanDraftValidator`, and `PlanPreviewRenderer` consistently.
