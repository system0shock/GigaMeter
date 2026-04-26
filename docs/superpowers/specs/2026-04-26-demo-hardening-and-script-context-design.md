# Demo Hardening And Script Context Design

**Date:** 2026-04-26

**Goal**

Prepare the current branch for tomorrow's demo by first closing demo-critical technical debt from `TODO.md`, then adding controlled full-context support for the currently selected JSR223 script in AI requests.

## Scope

Included:

- Fix AI conversation assembly in `OpenAiService` so prior assistant replies are not injected as `system` messages.
- Strengthen machine-readable contracts and failure handling for demo-critical AI flows.
- Harden `@plan` validation, malformed AI output handling, and fallback messaging.
- Stabilize the response shape of `@this` and `@optimize` without a large refactor.
- Polish visible UI/UX issues for the demo: dark theme rendering, code block colors, mojibake, and text readability.
- Add full context for the selected JSR223 element after the demo-critical TODO items are complete.

Excluded:

- `@plan import <url>`.
- A global redesign of prompt architecture.
- A full refactor of all AI services around a new conversation model.

## Phase 1: Demo-Critical TODO Cleanup

### 1. OpenAI Conversation Semantics

Problem:
`OpenAiService` currently adds prior assistant replies as `system` messages. That can break instruction hierarchy and make model behavior less predictable.

Decision:

- Build request messages with correct `system`, `user`, and `assistant` roles.
- Keep the existing history limit, but remove role distortion.
- Reduce logging risk by recording metadata and short previews instead of full chat content.

### 2. Structured And Safe Demo Paths

Problem:
Some demo flows already depend on structured outputs, but contracts and failure paths are not fully aligned yet.

Decision:

- Keep `@lint` on a strict JSON contract and verify its failure path.
- Strengthen `@plan` handling for malformed or non-JSON model output and validation failures.
- Give `@this` and `@optimize` a more stable, concise, actionable response format without introducing a heavy protocol unless required for the demo.

### 3. UI Polish For Demo

Problem:
Tomorrow's demo depends more on visible stability than on hidden architectural cleanliness. There are already local theme-aware fixes, but visible issues still need cleanup.

Decision:

- Finish theme-aware rendering in `MessageProcessor` and related GUI components.
- Fix the most visible mojibake strings in the chat and demo UI path.
- Keep the current UX shape and avoid expanding UI scope with new controls.

## Phase 2: Full Script Context

### 1. Context Boundary

Full script context should be added only for the currently selected JSR223 element, not for the entire test plan. This keeps token usage controlled and lowers the risk of degraded responses.

### 2. Included Data

The AI context for a selected JSR223 element should include:

- element type;
- element name;
- parent or tree path;
- `scriptLanguage`;
- full `script` content when it fits inside the configured limit;
- a `truncated` marker when the script must be cut down.

### 3. Where Context Applies

Full script context should be used only where it materially improves answer quality:

- regular chat when `selected` context is enabled;
- `@this` when it operates on the current element;
- `CodeCommandHandler` remains a separate code-specific path and should not be merged into general chat context.

`@plan analyze` should not be changed to load script bodies for the entire tree in this phase.

## Error Handling

- If the selected element is not JSR223, keep the existing summary-based context.
- If the script is empty, state that explicitly in the context payload.
- If the script is too long, truncate it and mark the payload as partial.
- If AI returns malformed output in a structured path, show a short, clear user-facing message and a corrective example where appropriate.

## Testing Strategy

- Add unit coverage for `OpenAiService` message assembly if that code can be isolated cleanly; otherwise add targeted regression coverage around the helper logic.
- Keep unit coverage for malformed AI response handling in `PlanCommandHandler`.
- Add unit coverage for JSR223 context building in `AiChatPanel` or in a dedicated helper if that logic is extracted.
- Run a manual demo walkthrough for:
  - regular chat without context;
  - regular chat with selected JSR223 context;
  - `@this`;
  - `@plan`;
  - `@lint` or `@optimize`.

## Success Criteria

- The main demo commands behave predictably and do not surface obvious garbage or broken UI.
- OpenAI request assembly no longer distorts assistant history via `system`.
- When a JSR223 element is selected, the model can see the script body in a controlled context path.
- The branch is ready for a short, reliable live demo without depending on `@plan import`.
