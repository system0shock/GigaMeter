# @plan Command Decomposition Design

## Goal

Decompose `@plan` handling so that `PlanCommandHandler` becomes a thin orchestration entry point while preserving current user-visible behavior for `@plan`, `@plan apply`, and `@plan analyze`.

## Scope

This design covers only the `@plan` flow:

- parsing the incoming command text
- generating a draft via `AiService`
- validating the generated draft
- previewing the draft
- applying the latest draft
- storing apply state for rollback

This design does not cover:

- refactoring `AiChatPanel`
- introducing a common provider abstraction for all AI services
- redesigning the Swing UI
- refactoring non-`@plan` undo flows

## Current Problems

- `PlanCommandHandler` mixes command parsing, prompt construction, JSON parsing, validation, preview rendering, apply orchestration, JMeter tree mutation, and analyze behavior in a single class.
- `apply` safety is hard to reason about because draft validation and apply orchestration are not clearly separated.
- Tests mostly cover the preview path and do not isolate parsing/routing decisions.
- `PlanApplyUndoStore` is a simple storage helper, but the surrounding orchestration depends on static state in a way that is hard to evolve.

## Proposed Architecture

Keep `PlanCommandHandler` as the public entry point, but split responsibilities into focused collaborators inside `org.gigameter.jmeter.ai.plan`.

### Components

`PlanCommandHandler`
- public entry point
- delegates parsing and generate-flow orchestration
- keeps existing `apply` and `analyze` behavior initially, then gradually delegates later

`PlanCommandRequest`
- immutable parsed representation of the incoming `@plan` command
- distinguishes `GENERATE`, `APPLY`, `ANALYZE`, and invalid usage

`PlanCommandParser`
- parses raw chat text into `PlanCommandRequest`
- centralizes usage and routing rules now embedded in `processPlanCommand`

`PlanDraftGenerator`
- builds the AI prompt
- calls `AiService`
- extracts and parses JSON

`PlanDraftValidator`
- validates structural requirements of the generated draft before save/apply

`PlanPreviewRenderer`
- converts a valid draft into the current markdown preview text

`PlanApplyUndoStore`
- remains a storage-only type for the latest applied thread group
- no orchestration logic beyond save/get/clear

## Data Flow

### Generate

1. `PlanCommandHandler.processPlanCommand()` receives raw message.
2. `PlanCommandParser` returns a `PlanCommandRequest`.
3. If mode is `GENERATE`, `PlanDraftGenerator` builds prompt, calls `AiService`, and parses JSON.
4. `PlanDraftValidator` validates the draft.
5. Draft is saved via `PlanDraftStore`.
6. `PlanPreviewRenderer` builds the preview text.

### Apply

1. `PlanCommandParser` returns `APPLY`.
2. `PlanCommandHandler` calls the existing apply path.
3. The apply path must operate only on a previously validated draft from `PlanDraftStore`.
4. Created thread group is saved to `PlanApplyUndoStore`.

### Analyze

1. `PlanCommandParser` returns `ANALYZE`.
2. `PlanCommandHandler` calls the existing analyze path.

## Error Handling

First iteration will keep user-facing messages stable where practical, but internal failures will be categorized more explicitly:

- invalid or empty command -> usage message
- AI returned empty or malformed content -> generate failure message
- invalid draft shape -> generate failure message
- no saved draft for apply -> existing hint to run `@plan <scenario>` first
- no GUI / no test plan root / failed JMeter mutation -> existing apply failure messages

The first iteration does not introduce a new exception hierarchy. Instead, focused collaborators throw narrow `IllegalStateException` or `Exception` where needed, and `PlanCommandHandler` remains responsible for mapping them to user-facing text.

## Testing Strategy

Add focused tests for:

- parsing `@plan`, `@plan apply`, `@plan analyze`, and blank input
- generate flow routing through parser/generator/validator/renderer
- stable preview behavior for existing valid drafts
- current `apply` and rollback guard messages still working

The first implementation slice will prefer constructor injection for new collaborators so handler tests can mock or stub them directly.

## Rollout Plan

Iteration 1:
- introduce parser, request model, generator, validator, and renderer
- refactor `PlanCommandHandler` generate path to use them
- keep apply/analyze internals in `PlanCommandHandler`

Iteration 2:
- extract apply orchestration into a dedicated `PlanApplyService`
- add explicit apply validation before mutation

Iteration 3:
- extract analyze flow into a dedicated analyzer if still justified by feature value

## Success Criteria

- `PlanCommandHandler` is materially smaller and easier to read
- generate path responsibilities are split into focused classes
- current tests still pass
- new tests cover parse/routing behavior directly
- no user-visible regression in `@plan` preview, `@plan apply`, or `@plan analyze`
