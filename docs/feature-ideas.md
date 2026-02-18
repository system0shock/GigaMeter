# Feature Ideas for GigaMeter

## High Priority

1. **`@code` in chat with `preview/apply`**
   - Bring back `@code` in chat with safe preview and explicit apply action for JSR223 changes.
   - Keep context-menu workflow, but make chat flow first-class and consistent with docs.

2. **Safe mode for bulk operations (`@lint`, `@wrap`)**
   - Add dry-run mode to show planned tree/name changes before applying.
   - Allow selective apply (checkbox-like selection in UI).

3. **Unified undo/redo for all AI operations**
   - Implement transactional history for all structural and naming changes.
   - Align UI behavior and backend logic for `@wrap` redo support.

4. **Usage and cost tracking for all providers**
   - Extend usage reporting beyond OpenAI to GigaChat and DeepSeek.
   - Add budget limits (daily/monthly) and threshold alerts.

## Product Features

5. **`@optimize` at multiple scopes**
   - Support optimization modes for selected element, Thread Group, and whole test plan.
   - Rank recommendations by impact and implementation effort.

6. **Generate test plan from scenario (`@plan`)**
   - Convert plain-language flow into initial JMeter tree with defaults.
   - Include optional load profile presets (smoke/load/stress).

7. **Run result analysis and regression insights**
   - Parse JTL/CSV and provide AI summary of degradations and likely causes.
   - Suggest concrete test-plan fixes.

8. **Diff-first editing UX**
   - Show before/after diff for all AI-proposed modifications.
   - Require user confirmation before apply.

9. **Privacy controls for prompts/context**
   - Mask secrets/tokens/PII before provider calls.
   - Add strict private mode with configurable redaction rules.

## Engineering Improvements (enablers)

10. **Architecture and quality improvements**
   - Decompose oversized UI/controller classes (especially `AiChatPanel`).
   - Consolidate duplicated element-management logic.
   - Fix mojibake/encoding issues in UI strings.
   - Expand test coverage for wrap/lint/services and failure paths.
