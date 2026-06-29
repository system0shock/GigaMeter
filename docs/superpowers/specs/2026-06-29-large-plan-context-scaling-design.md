# Large-plan context scaling — design

Date: 2026-06-29
Status: Approved (ready for implementation planning)

## Problem

On large legacy test plans (dozens of thread groups, deeply nested configs), the
CLI agent does **not see the whole plan**. `JMeterPlanSerializer.serialize()` caps
the tree at `DEFAULT_MAX_ELEMENTS = 300` and sets `truncated = true`; the readable
tree handed to the agent contains only the first ~300 elements in DFS order. The
tail of the plan is silently lost, so the agent answers only about the elements it
can see.

Observed symptom (confirmed with the user): **truncation** — not token-limit
errors, not slowness, not model confusion. The root cause is the context strategy
itself: the plugin always emits the *entire* serialized tree as a flat text dump,
which does not scale.

## Goals

- The agent must be able to reason over the **whole** plan regardless of size —
  no element is ever silently dropped.
- Support both working modes equally (user reported ~50/50):
  - **Localized** — questions/edits about one thread group / config block.
  - **Cross-cutting** — reasoning across the whole plan ("find all samplers
    missing assertions across all 40 thread groups").
- Primary use is **analysis** (breadth matters more than per-element prop depth);
  global edits via `jmeter-ops` are rarer but must stay correct (exact `#id`).
- No change to the `jmeter-ops` protocol or to `#id` semantics.

## Non-goals

- Stage 2 (`get_subtree` on-demand drill-down) is **documented but not built** in
  this iteration (see "Future: Stage 2").
- No change to cloud providers, CLI transport, or session handling.
- Not solving token-limit / slowness symptoms (not the reported failure mode);
  the budget guard bounds size as a safety net, not as the primary aim.

## Current architecture (for reference)

- `JMeterPlanSerializer.serialize(root, maxElements=300, maxDepth=20)` does a DFS,
  assigns sequential `#id`s, extracts "useful" props per element, and flags
  `truncated` when nodes remain past the cap.
- `SerializedPlan.toReadableTree()` renders **all** collected elements as an
  indented text tree with inline props.
- `AiChatPanel.currentTreeContext()` serializes the whole plan via `planRoot()` +
  `serialize(root)` and emits `toReadableTree()` as the context block, plus
  `revisionHash()` for the apply-time staleness guard.
- The block is injected by `withCliOpsContext()` (first / non-session turn) and
  re-sent by `buildCliSessionTurn()` whenever `revisionHash` changes.
- Selection is already readable: `GuiPackage.getTreeListener().getCurrentNode()`
  and `getSelectedNodes()` (multi-select array) — the latter already used by
  `ElementRenamer`.

## Design — two-layer context (Stage 1)

A new `PlanContextBuilder` (or an extension of `JMeterPlanSerializer`) assembles the
agent context from two layers, within a size budget:

1. **Skeleton** — the whole plan for breadth.
2. **Detail** — the mouse-selected subtrees for depth.

Wired in at `AiChatPanel.currentTreeContext()`, replacing the single
`toReadableTree()` call. `revisionHash()` stays whole-plan (apply guard unchanged).

### Layer 1 — Skeleton (whole plan, always)

- One line per element: `#<id> <indent>[<type>] "<name>"`. Ordinary skeleton lines
  are **prop-light** — no props, or at most 1–2 structurally key props (e.g.
  `users=` for a thread group). (Collapse representatives may carry their
  distinguishing inline props — see representation A below — since they stand in for
  the whole group.)
- **No element-count truncation** — skeleton lines are tiny, so the whole plan
  fits. The 300-cap no longer truncates the skeleton (raised far / removed for the
  skeleton path; depth cap retained as a sanity bound).
- **Sibling collapse by structural fingerprint** (see below) to remove repetition.

### Structural fingerprint + sibling collapse

- Per-node **recursive subtree hash**: `type + tracked props + hashes of children`
  (same notion as `revisionHash()`, computed per node during DFS).
- Sibling elements under the same parent that share a subtree hash are grouped.
- Collapse only when `count >= N` (default **N = 3**); singles and pairs always
  render in full.
- **Representation A — representative + references:**
  ```
  #45 [Header Manager] "Auth" | Content-Type, Authorization
  #52 [Header Manager] "Auth" ≡ #45
  #59 [Header Manager] "Auth" ≡ #45
  ```
  The representative renders fully; the rest collapse to a one-line `≡ #repr`.
  Every `#id` remains present and addressable for `jmeter-ops`.
- **Collapse is by structure, never by name.** Legacy case — 10 thread groups all
  named "Дебаг" but with different content — produces 10 different subtree hashes,
  so they are **not** collapsed; each renders separately and in full. Only
  genuinely structure-identical siblings (e.g. 40 identical Header Managers) fold.

### Layer 2 — Detail (selected subtrees, always)

- `getTreeListener().getSelectedNodes()` → for each selected node, render its full
  subtree with props (the current `toReadableTree` detail level).
- **Dedup against the skeleton:** a selected subtree appears collapsed in the
  skeleton (marked e.g. `раскрыто ниже ↓`) and is rendered in full only in the
  detail layer — no double render.
- **Overlapping / nested selections** collapse to the topmost selected ancestor.
- **Nothing selected** → skeleton only (depth deferred to Stage 2).

### Budget guard

- Size limit by chars/tokens via property `gigameter.context.max.chars`
  (default TBD during implementation, chosen from measured real plans).
- Priority order when over budget (breadth first):
  1. Render skeleton (full breadth).
  2. Add selected detail.
  3. If still over: compact the detail layer, then compact the skeleton further.
  4. Emit a **visible** chat note ("контекст урезан, уточните выделение").
- **Never silently truncate.** Degradation is always surfaced to the user.

### Prompt format

```
СТРУКТУРА ПЛАНА (скелет, #id для операций jmeter-ops):
<skeleton with sibling collapse>

ДЕТАЛИ ВЫДЕЛЕННЫХ ВЕТОК:
<full subtrees of selected nodes>
```

`#id` semantics and the `jmeter-ops` protocol are unchanged; edits work as before.

### Session re-send key

`buildCliSessionTurn()` re-sends context when the tree changes. Extend the
comparison key from `revisionHash` alone to `revisionHash + selectionHash`, so
changing the mouse selection (which changes which subtrees are detailed) triggers a
fresh context send within an active session.

## Edge cases

- Legacy same-name different-content siblings → not collapsed (structural hash).
- Selection changed mid-session → re-send via `revisionHash + selectionHash`.
- Huge selection → bounded by the budget guard with a visible note.
- Collapsed elements remain addressable by `#id` (representation A).
- Empty plan / no Test Plan node → existing `planRoot()` fallback behavior.

## Future: Stage 2 (not in this plan)

On-demand drill-down: a new `get_subtree #id` operation the agent emits to request
the full detail of an arbitrary branch; the plugin answers in a follow-up turn.
Covers cross-cutting edits when nothing is selected. Requires a plugin-mediated
request→response loop over the single-shot CLI (the reason Stage 5/MCP was
previously deferred). Tracked as the next phase, not built here.

## Affected code (anticipated)

- `JMeterPlanSerializer` — per-node subtree hash; skeleton renderer with sibling
  collapse; (skeleton path uncapped by element count).
- New `PlanContextBuilder` (or methods on the serializer) — two-layer assembly +
  budget guard.
- `AiChatPanel.currentTreeContext()` / `buildCliSessionTurn()` — use the builder;
  read `getSelectedNodes()`; extend the session re-send key with `selectionHash`.
- `jmeter-ai-sample.properties` — document `gigameter.context.max.chars` and the
  collapse threshold.

## Testing

Unit tests (alongside the existing `AiChatPanelContextBuilderTest`):

- Subtree-hash determinism (same structure → same hash; differing props/children →
  different hash).
- Sibling collapse: N identical siblings fold to representative + `≡ #id`; 10
  same-name different-content thread groups do **not** fold.
- Skeleton is never element-count truncated (large synthetic plan → every `#id`
  present).
- Selected subtrees: full detail included; deduped against skeleton; nested
  selections collapse to the topmost.
- Budget guard: degradation order is breadth-first; over-budget emits a visible
  note rather than silent truncation.
