# Large-plan context scaling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop silent truncation of large JMeter plans by sending the agent a compact whole-plan *skeleton* (breadth) plus full detail of the mouse-selected subtrees (depth), within a size budget.

**Architecture:** A new `PlanSkeleton` renders the whole plan prop-light with sibling collapse keyed on a recursive structural fingerprint (so legacy same-name/different-content siblings never fold). A new `PlanContextBuilder` assembles two layers вЂ” skeleton + selected-subtree detail вЂ” and enforces a char budget with visible (never silent) degradation. `AiChatPanel.currentTreeContext()` switches to the builder and feeds it the ids of `getSelectedNodes()`; the session re-send key gains a selection hash. All new logic is pure and operates on `List<JMeterPlanSerializer.ElementEntry>`, so tests need no live JMeter tree.

**Tech Stack:** Java 11, JUnit 5 (Jupiter), Maven (offline). Existing classes: `org.gigameter.jmeter.ai.utils.JMeterPlanSerializer`, `org.gigameter.jmeter.ai.gui.AiChatPanel`.

## Global Constraints

- Java 11 (`maven.compiler.source/target=11`). No `var`, no records, no switch-expressions.
- Build/test OFFLINE with the local Maven only:
  `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test`
  Run one test class: append `-Dtest=ClassName`.
- Working directory is the git repo root `F:\Coding\Jmeter-ai-plugin\jmeter-ai`. Branch `main`.
- No new third-party dependencies (the build was just stripped of `openai-java`; keep it dependency-light).
- `#id` semantics and the `jmeter-ops` protocol must not change вЂ” ids stay the whole-plan DFS ids (`id == listIndex + 1`).
- New code lives in package `org.gigameter.jmeter.ai.utils`. Russian user-facing strings, matching existing tone.
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

## Existing facts the implementer needs

- `JMeterPlanSerializer.serialize(JMeterTreeNode root, int maxElements, int maxDepth)` в†’ `SerializedPlan`.
- `SerializedPlan` public fields: `List<ElementEntry> elements`, `Map<Integer,JMeterTreeNode> nodeById`, `boolean truncated`. Methods: `toJson()`, `toJson(int,int)`, `toReadableTree()`, `revisionHash()`, `size()`.
- `ElementEntry` PUBLIC constructor: `ElementEntry(int id, int depth, String type, String name, Map<String,String> props)`; public final fields `id, depth, type, name, props`.
- Ids are sequential from 1 in DFS preorder with no gaps, so `elements.get(k).id == k + 1`.
- `friendlyTypeName(String)`, `inlineProps(String,Map)`, `buildReadableTree(List,boolean)` exist but are `private static` in `JMeterPlanSerializer`.
- `AiChatPanel.currentTreeContext()` (~line 1777) returns `String[]{tree, revision}`. `buildCliSessionTurn()` (~line 1801) re-sends context when `revision != lastSentTreeRevision`.
- `AiConfig.getProperty(String key, String def)` reads JMeter properties.

## File structure

- **Modify** `src/main/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializer.java`
  - Add `public static final int SKELETON_MAX_ELEMENTS = 5000;`
  - Add public static delegates `friendlyType(String)` and `inlineSummary(String,Map)`.
  - Add public static `subtreeEnd(List<ElementEntry>, int idx)`.
  - Add `SerializedPlan.toReadableTree(int fromIdx, int toIdx)`.
- **Create** `src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java`
  - `subtreeHashes(...)`, `render(...)`.
- **Create** `src/main/java/org/gigameter/jmeter/ai/utils/PlanContextBuilder.java`
  - `topmostSelected(...)`, `selectionHash(...)`, `build(...)`.
- **Modify** `src/main/java/org/gigameter/jmeter/ai/gui/AiChatPanel.java`
  - `currentTreeContext()`, new `selectedIds(...)`, `buildCliSessionTurn()` re-send key.
- **Modify** `jmeter-ai-sample.properties` вЂ” document new properties.
- **Create** tests under `src/test/java/org/gigameter/jmeter/ai/utils/`.

---

### Task 1: Serializer helpers вЂ” `subtreeEnd`, public type/prop delegates, detail subrange

**Files:**
- Modify: `src/main/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializer.java`
- Test: `src/test/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializerHelpersTest.java`

**Interfaces:**
- Produces:
  - `public static final int JMeterPlanSerializer.SKELETON_MAX_ELEMENTS` (= 5000)
  - `public static int JMeterPlanSerializer.subtreeEnd(List<ElementEntry> elements, int idx)` вЂ” exclusive end index of the subtree rooted at `idx` (preorder + depth).
  - `public static String JMeterPlanSerializer.friendlyType(String type)`
  - `public static String JMeterPlanSerializer.inlineSummary(String type, Map<String,String> props)`
  - `public String SerializedPlan.toReadableTree(int fromIdx, int toIdx)` вЂ” readable tree (full inline props) over the sublist `[fromIdx, toIdx)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializerHelpersTest.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JMeterPlanSerializerHelpersTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    /** Tree: #1 TestPlan(d0) > [#2 TG(d1) > #3 Sampler(d2)], #4 TG(d1) */
    private static List<ElementEntry> sample() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /"));
        l.add(e(4, 1, "ThreadGroup", "TG B"));
        return l;
    }

    @Test
    void subtreeEndCoversDescendants() {
        List<ElementEntry> l = sample();
        assertEquals(4, JMeterPlanSerializer.subtreeEnd(l, 1)); // TG A subtree = idx 1,2 -> end 3? see note
    }

    @Test
    void subtreeEndOfLeafIsNext() {
        List<ElementEntry> l = sample();
        assertEquals(3, JMeterPlanSerializer.subtreeEnd(l, 2)); // leaf #3 -> end 3
    }

    @Test
    void friendlyTypeMapsKnownClass() {
        assertEquals("Thread Group", JMeterPlanSerializer.friendlyType("ThreadGroup"));
    }

    @Test
    void toReadableTreeRangeRendersOnlySublist() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("HTTPSampler.method", "GET");
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(new ElementEntry(2, 1, "ThreadGroup", "TG A", new LinkedHashMap<>()));
        l.add(new ElementEntry(3, 2, "HTTPSamplerProxy", "GET /", props));
        JMeterPlanSerializer.SerializedPlan plan =
                new JMeterPlanSerializer.SerializedPlan(l, new LinkedHashMap<>(), false);
        String out = plan.toReadableTree(1, 3); // TG A + sampler only
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("#2"));
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("#3"));
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("#1"));
    }
}
```

Note on `subtreeEndCoversDescendants`: TG A is idx 1, its only descendant is idx 2; the next element at depth в‰¤ 1 is idx 3 (TG B), so `subtreeEnd(l,1)` must return **3**. Fix the expected value to `3`:

```java
    @Test
    void subtreeEndCoversDescendants() {
        List<ElementEntry> l = sample();
        assertEquals(3, JMeterPlanSerializer.subtreeEnd(l, 1));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=JMeterPlanSerializerHelpersTest`
Expected: FAIL вЂ” `subtreeEnd` / `friendlyType` / `toReadableTree(int,int)` not defined (compile error).

- [ ] **Step 3: Add the helpers to `JMeterPlanSerializer`**

Add the constant near `DEFAULT_MAX_DEPTH`:

```java
    public static final int SKELETON_MAX_ELEMENTS = 5000;
```

Add these public static methods to the `JMeterPlanSerializer` class body (top level, not inside a nested class):

```java
    /** Exclusive end index of the subtree rooted at {@code idx} (preorder + depth list). */
    public static int subtreeEnd(List<ElementEntry> elements, int idx) {
        int depth = elements.get(idx).depth;
        int j = idx + 1;
        while (j < elements.size() && elements.get(j).depth > depth) {
            j++;
        }
        return j;
    }

    /** Public access to the friendly label mapping (used by PlanSkeleton). */
    public static String friendlyType(String type) {
        return friendlyTypeName(type);
    }

    /** Public access to the inline prop summary (used by PlanSkeleton representatives). */
    public static String inlineSummary(String type, Map<String, String> props) {
        return inlineProps(type, props);
    }
```

Add to the `SerializedPlan` class body:

```java
        /** Readable tree (full inline props) over the sublist {@code [fromIdx, toIdx)}. */
        public String toReadableTree(int fromIdx, int toIdx) {
            List<ElementEntry> sub = elements.subList(
                    Math.max(0, fromIdx),
                    Math.min(toIdx, elements.size()));
            return buildReadableTree(sub, false);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=JMeterPlanSerializerHelpersTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializer.java src/test/java/org/gigameter/jmeter/ai/utils/JMeterPlanSerializerHelpersTest.java
git commit -m "feat: serializer helpers for skeleton/detail context (subtreeEnd, friendlyType, detail subrange)"
```

---

### Task 2: Recursive subtree fingerprint (`PlanSkeleton.subtreeHashes`)

**Files:**
- Create: `src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java`
- Test: `src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonHashTest.java`

**Interfaces:**
- Consumes: `JMeterPlanSerializer.subtreeEnd`, `ElementEntry`.
- Produces: `public static Map<Integer,String> PlanSkeleton.subtreeHashes(List<ElementEntry> elements)` вЂ” maps each element id to a hash of `type + name + props + ordered child subtree hashes`. Identical subtrees share a hash; structurally different ones differ.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonHashTest.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlanSkeletonHashTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    @Test
    void identicalSiblingSubtreesShareHash() {
        // #1 Plan > [#2 HM "Auth", #3 HM "Auth"] вЂ” identical leaves
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "HeaderManager", "Auth"));
        l.add(e(3, 1, "HeaderManager", "Auth"));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertEquals(h.get(2), h.get(3));
    }

    @Test
    void sameNameDifferentChildrenDifferHash() {
        // Two thread groups both named "Р”РµР±Р°Рі" but different content
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "Р”РµР±Р°Рі"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "Р”РµР±Р°Рі"));
        l.add(e(5, 2, "HTTPSamplerProxy", "GET /b"));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertNotEquals(h.get(2), h.get(4));
    }

    @Test
    void differentPropsDifferHash() {
        Map<String, String> p1 = new LinkedHashMap<>();
        p1.put("HTTPSampler.path", "/a");
        Map<String, String> p2 = new LinkedHashMap<>();
        p2.put("HTTPSampler.path", "/b");
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(new ElementEntry(2, 1, "HTTPSamplerProxy", "S", p1));
        l.add(new ElementEntry(3, 1, "HTTPSamplerProxy", "S", p2));
        Map<Integer, String> h = PlanSkeleton.subtreeHashes(l);
        assertNotEquals(h.get(2), h.get(3));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanSkeletonHashTest`
Expected: FAIL вЂ” `PlanSkeleton` does not exist (compile error).

- [ ] **Step 3: Create `PlanSkeleton` with `subtreeHashes`**

Create `src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the whole plan as a compact, prop-light skeleton with sibling collapse keyed on a
 * recursive structural fingerprint. Pure functions over the preorder+depth element list, so they
 * are unit-testable without a live JMeter tree.
 */
public final class PlanSkeleton {

    private PlanSkeleton() {
    }

    /** Maps each element id to a hash of its full subtree (type + name + props + children). */
    public static Map<Integer, String> subtreeHashes(List<ElementEntry> elements) {
        Map<Integer, String> byId = new LinkedHashMap<>();
        if (!elements.isEmpty()) {
            hashAt(elements, 0, byId);
        }
        return byId;
    }

    /** Returns the hash for the subtree at {@code idx}, recording it (and descendants) into {@code out}. */
    private static String hashAt(List<ElementEntry> elements, int idx, Map<Integer, String> out) {
        ElementEntry e = elements.get(idx);
        int depth = e.depth;
        StringBuilder sb = new StringBuilder();
        sb.append(e.type).append("\u0001").append(e.name).append("\u0001").append(e.props);
        int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
        int j = idx + 1;
        while (j < end) {
            if (elements.get(j).depth == depth + 1) {
                sb.append('(').append(hashAt(elements, j, out)).append(')');
            }
            j = JMeterPlanSerializer.subtreeEnd(elements, j);
        }
        String h = Integer.toHexString(sb.toString().hashCode());
        out.put(e.id, h);
        return h;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanSkeletonHashTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonHashTest.java
git commit -m "feat: recursive subtree fingerprint for sibling collapse"
```

---

### Task 3: Skeleton renderer with sibling collapse (`PlanSkeleton.render`)

**Files:**
- Modify: `src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java`
- Test: `src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonRenderTest.java`

**Interfaces:**
- Consumes: `subtreeHashes`, `JMeterPlanSerializer.subtreeEnd/friendlyType/inlineSummary`.
- Produces: `public static String PlanSkeleton.render(List<ElementEntry> elements, int collapseThreshold, java.util.Set<Integer> expandedIds)`.
  - Every element line: `#<id> <indent>в””в”Ђ [<friendlyType>] "<name>"`.
  - A sibling group sharing a subtree hash with `count >= collapseThreshold`: the first occurrence renders fully (its line may append ` | <inlineSummary>`), its subtree is rendered; every later occurrence renders one line `... "<name>" в‰Ў #<repId>` and its subtree is skipped.
  - An id in `expandedIds` renders one line `... "<name>" (СЂР°СЃРєСЂС‹С‚Рѕ РЅРёР¶Рµ в†“)` and its subtree is skipped (it appears in the detail layer). `expandedIds` takes priority over collapse.
  - Never drops an element line.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonRenderTest.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanSkeletonRenderTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    private static List<ElementEntry> withIdenticalSiblings(int count) {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        for (int i = 0; i < count; i++) {
            l.add(e(2 + i, 1, "HeaderManager", "Auth"));
        }
        return l;
    }

    @Test
    void collapsesThreeOrMoreIdenticalSiblings() {
        String out = PlanSkeleton.render(withIdenticalSiblings(3), 3, Collections.emptySet());
        assertTrue(out.contains("#2"));            // representative
        assertTrue(out.contains("в‰Ў #2"));          // collapsed refs point to #2
        assertTrue(out.contains("#3"));            // ids preserved
        assertTrue(out.contains("#4"));
    }

    @Test
    void doesNotCollapseBelowThreshold() {
        String out = PlanSkeleton.render(withIdenticalSiblings(2), 3, Collections.emptySet());
        assertFalse(out.contains("в‰Ў #"));
    }

    @Test
    void doesNotCollapseSameNameDifferentContent() {
        // Two "Р”РµР±Р°Рі" thread groups with different children -> different hashes -> no collapse
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "Р”РµР±Р°Рі"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "Р”РµР±Р°Рі"));
        l.add(e(5, 2, "HTTPSamplerProxy", "GET /b"));
        l.add(e(6, 1, "ThreadGroup", "Р”РµР±Р°Рі"));
        l.add(e(7, 2, "HTTPSamplerProxy", "GET /c"));
        String out = PlanSkeleton.render(l, 3, Collections.emptySet());
        assertFalse(out.contains("в‰Ў #"));
        assertTrue(out.contains("GET /a"));
        assertTrue(out.contains("GET /b"));
        assertTrue(out.contains("GET /c"));
    }

    @Test
    void expandedNodeShowsMarkerAndSkipsSubtree() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG"));
        l.add(e(3, 2, "HTTPSamplerProxy", "secret-detail"));
        Set<Integer> expanded = new HashSet<>();
        expanded.add(2);
        String out = PlanSkeleton.render(l, 3, expanded);
        assertTrue(out.contains("СЂР°СЃРєСЂС‹С‚Рѕ РЅРёР¶Рµ"));
        assertFalse(out.contains("secret-detail")); // subtree skipped in skeleton
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanSkeletonRenderTest`
Expected: FAIL вЂ” `render` not defined (compile error).

- [ ] **Step 3: Add `render` to `PlanSkeleton`**

Add these imports to `PlanSkeleton.java`:

```java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
```

Add to the `PlanSkeleton` class body:

```java
    /** Renders the whole plan prop-light with sibling collapse and expanded-node markers. */
    public static String render(List<ElementEntry> elements, int collapseThreshold, Set<Integer> expandedIds) {
        StringBuilder sb = new StringBuilder();
        if (elements.isEmpty()) {
            return sb.toString();
        }
        Map<Integer, String> hashes = subtreeHashes(elements);
        Set<Integer> expanded = expandedIds == null ? java.util.Collections.emptySet() : expandedIds;
        renderSubtree(elements, 0, hashes, collapseThreshold, expanded, false, sb);
        return sb.toString();
    }

    private static void renderSubtree(List<ElementEntry> elements, int idx, Map<Integer, String> hashes,
                                      int threshold, Set<Integer> expanded, boolean showProps, StringBuilder sb) {
        ElementEntry e = elements.get(idx);
        appendLine(e, showProps ? JMeterPlanSerializer.inlineSummary(e.type, e.props) : "", null, false, sb);

        List<Integer> children = directChildren(elements, idx);
        Map<String, Integer> counts = new HashMap<>();
        for (int ci : children) {
            String h = hashes.get(elements.get(ci).id);
            counts.merge(h, 1, Integer::sum);
        }
        Map<String, Integer> repId = new HashMap<>();
        for (int ci : children) {
            ElementEntry child = elements.get(ci);
            if (expanded.contains(child.id)) {
                appendLine(child, "", null, true, sb); // expanded marker, skip subtree
                continue;
            }
            String h = hashes.get(child.id);
            if (counts.get(h) >= threshold) {
                Integer rep = repId.get(h);
                if (rep == null) {
                    repId.put(h, child.id);
                    renderSubtree(elements, ci, hashes, threshold, expanded, true, sb); // representative w/ props
                } else {
                    appendLine(child, "", rep, false, sb); // в‰Ў #rep, skip subtree
                }
            } else {
                renderSubtree(elements, ci, hashes, threshold, expanded, false, sb);
            }
        }
    }

    private static List<Integer> directChildren(List<ElementEntry> elements, int idx) {
        List<Integer> out = new ArrayList<>();
        int depth = elements.get(idx).depth;
        int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
        int j = idx + 1;
        while (j < end) {
            if (elements.get(j).depth == depth + 1) {
                out.add(j);
            }
            j = JMeterPlanSerializer.subtreeEnd(elements, j);
        }
        return out;
    }

    /** Appends one skeleton line. {@code refId} non-null => "в‰Ў #refId"; {@code expandedMarker} => "(СЂР°СЃРєСЂС‹С‚Рѕ РЅРёР¶Рµ в†“)". */
    private static void appendLine(ElementEntry e, String inline, Integer refId, boolean expandedMarker, StringBuilder sb) {
        String indent = repeat("  ", Math.max(0, e.depth));
        sb.append('#').append(e.id).append(' ').append(indent)
          .append("в””в”Ђ [").append(JMeterPlanSerializer.friendlyType(e.type)).append("] \"").append(e.name).append('"');
        if (refId != null) {
            sb.append(" в‰Ў #").append(refId);
        } else if (expandedMarker) {
            sb.append(" (СЂР°СЃРєСЂС‹С‚Рѕ РЅРёР¶Рµ в†“)");
        } else if (inline != null && !inline.isEmpty()) {
            sb.append(" | ").append(inline);
        }
        sb.append('\n');
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(s);
        }
        return b.toString();
    }
```

(Java 11 has `String.repeat`, but `"  ".repeat` is used elsewhere; the local `repeat` helper keeps this class self-contained вЂ” either is fine.)

- [ ] **Step 4: Run test to verify it passes**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanSkeletonRenderTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/utils/PlanSkeleton.java src/test/java/org/gigameter/jmeter/ai/utils/PlanSkeletonRenderTest.java
git commit -m "feat: skeleton renderer with structural sibling collapse"
```

---

### Task 4: Two-layer assembly + budget guard (`PlanContextBuilder`)

**Files:**
- Create: `src/main/java/org/gigameter/jmeter/ai/utils/PlanContextBuilder.java`
- Test: `src/test/java/org/gigameter/jmeter/ai/utils/PlanContextBuilderTest.java`

**Interfaces:**
- Consumes: `SerializedPlan` (`elements`, `toReadableTree(int,int)`), `PlanSkeleton.render`, `JMeterPlanSerializer.subtreeEnd`.
- Produces:
  - `public static List<Integer> PlanContextBuilder.topmostSelected(List<ElementEntry> elements, java.util.Collection<Integer> selectedIds)` вЂ” drops any selected id that is a descendant of another selected id; preserves document order.
  - `public static String PlanContextBuilder.selectionHash(java.util.Collection<Integer> selectedIds)` вЂ” order-independent stable hash of the selection.
  - `public static String PlanContextBuilder.build(SerializedPlan plan, java.util.Collection<Integer> selectedIds, int collapseThreshold, int maxChars)` вЂ” full two-layer context string.
    - Always contains the skeleton section header `РЎРўР РЈРљРўРЈР Рђ РџР›РђРќРђ`.
    - For each topmost selected id, a detail section under `Р”Р•РўРђР›Р Р’Р«Р”Р•Р›Р•РќРќР«РҐ Р’Р•РўРћРљ` rendered via `plan.toReadableTree(idIdx, subtreeEnd)`, reusing whole-plan ids.
    - Budget: append detail subtrees in order until the next would exceed `maxChars`; ones that don't fit are listed as `(РґРµС‚Р°Р»Рё #<id> РЅРµ РІР»РµР·Р»Рё РІ Р±СЋРґР¶РµС‚ вЂ” РІС‹РґРµР»РёС‚Рµ РјРµРЅСЊС€Рµ)`. If the skeleton alone exceeds `maxChars`, include it anyway and append `(вљ  РїР»Р°РЅ РѕС‡РµРЅСЊ Р±РѕР»СЊС€РѕР№: ...)`. Never silently truncate.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/gigameter/jmeter/ai/utils/PlanContextBuilderTest.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanContextBuilderTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    /** #1 Plan > [#2 TG A > #3 sampler], #4 TG B */
    private static SerializedPlan plan() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        l.add(e(4, 1, "ThreadGroup", "TG B"));
        return new SerializedPlan(l, new LinkedHashMap<>(), false);
    }

    @Test
    void buildHasSkeletonAndDetailSections() {
        String out = PlanContextBuilder.build(plan(), Arrays.asList(2), 3, 100000);
        assertTrue(out.contains("РЎРўР РЈРљРўРЈР Рђ РџР›РђРќРђ"));
        assertTrue(out.contains("Р”Р•РўРђР›Р Р’Р«Р”Р•Р›Р•РќРќР«РҐ Р’Р•РўРћРљ"));
        assertTrue(out.contains("#2"));
        assertTrue(out.contains("GET /a")); // detail of selected TG A includes its sampler
    }

    @Test
    void topmostSelectedDropsDescendantOfSelectedAncestor() {
        // select both TG A (#2) and its sampler (#3) -> only #2 remains
        List<Integer> top = PlanContextBuilder.topmostSelected(plan().elements, Arrays.asList(2, 3));
        assertEquals(Arrays.asList(2), top);
    }

    @Test
    void selectionHashIsOrderIndependent() {
        assertEquals(
                PlanContextBuilder.selectionHash(Arrays.asList(2, 4)),
                PlanContextBuilder.selectionHash(Arrays.asList(4, 2)));
    }

    @Test
    void overBudgetEmitsVisibleNoteNotSilentDrop() {
        // tiny budget: skeleton fits but detail does not
        String out = PlanContextBuilder.build(plan(), Arrays.asList(2), 3, 1);
        assertTrue(out.contains("РЎРўР РЈРљРўРЈР Рђ РџР›РђРќРђ"));     // skeleton always present
        assertTrue(out.contains("РЅРµ РІР»РµР·Р»Рё РІ Р±СЋРґР¶РµС‚") || out.contains("РѕС‡РµРЅСЊ Р±РѕР»СЊС€РѕР№"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanContextBuilderTest`
Expected: FAIL вЂ” `PlanContextBuilder` does not exist (compile error).

- [ ] **Step 3: Create `PlanContextBuilder`**

Create `src/main/java/org/gigameter/jmeter/ai/utils/PlanContextBuilder.java`:

```java
package org.gigameter.jmeter.ai.utils;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assembles the two-layer agent context: a whole-plan skeleton (breadth) plus full detail of the
 * selected subtrees (depth), within a char budget. Degradation is always surfaced, never silent.
 */
public final class PlanContextBuilder {

    private PlanContextBuilder() {
    }

    /** Drops any selected id that is a descendant of another selected id; preserves order. */
    public static List<Integer> topmostSelected(List<ElementEntry> elements, Collection<Integer> selectedIds) {
        Set<Integer> selected = new HashSet<>(selectedIds);
        List<Integer> out = new ArrayList<>();
        for (ElementEntry e : elements) {
            if (!selected.contains(e.id)) {
                continue;
            }
            int idx = e.id - 1;
            int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
            boolean hasSelectedAncestor = false;
            for (int a = 0; a < idx; a++) {
                if (selected.contains(elements.get(a).id)
                        && JMeterPlanSerializer.subtreeEnd(elements, a) > idx) {
                    hasSelectedAncestor = true;
                    break;
                }
            }
            if (!hasSelectedAncestor) {
                out.add(e.id);
            }
        }
        return out;
    }

    /** Order-independent stable hash of the selection (empty selection => "none"). */
    public static String selectionHash(Collection<Integer> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Integer id : new TreeSet<>(selectedIds)) {
            sb.append(id).append(',');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    public static String build(SerializedPlan plan, Collection<Integer> selectedIds,
                               int collapseThreshold, int maxChars) {
        List<ElementEntry> elements = plan.elements;
        List<Integer> topmost = topmostSelected(elements, selectedIds);
        Set<Integer> expanded = new HashSet<>(topmost);

        String skeleton = PlanSkeleton.render(elements, collapseThreshold, expanded);

        StringBuilder sb = new StringBuilder();
        sb.append("РЎРўР РЈРљРўРЈР Рђ РџР›РђРќРђ (СЃРєРµР»РµС‚, #id РґР»СЏ РѕРїРµСЂР°С†РёР№ jmeter-ops):\n");
        sb.append(skeleton);

        if (sb.length() > maxChars) {
            sb.append("\n(вљ  РїР»Р°РЅ РѕС‡РµРЅСЊ Р±РѕР»СЊС€РѕР№: РєРѕРЅС‚РµРєСЃС‚ РЅРµ СѓР¶Р°С‚ РґРѕ Р±СЋРґР¶РµС‚Р°, РІРѕР·РјРѕР¶РЅС‹ РѕРіСЂР°РЅРёС‡РµРЅРёСЏ РјРѕРґРµР»Рё)\n");
            return sb.toString();
        }

        if (topmost.isEmpty()) {
            return sb.toString();
        }

        sb.append("\nР”Р•РўРђР›Р Р’Р«Р”Р•Р›Р•РќРќР«РҐ Р’Р•РўРћРљ:\n");
        List<Integer> overflow = new ArrayList<>();
        for (Integer id : topmost) {
            int idx = id - 1;
            int end = JMeterPlanSerializer.subtreeEnd(elements, idx);
            String detail = plan.toReadableTree(idx, end);
            if (sb.length() + detail.length() > maxChars) {
                overflow.add(id);
                continue;
            }
            sb.append(detail);
        }
        for (Integer id : overflow) {
            sb.append("(РґРµС‚Р°Р»Рё #").append(id).append(" РЅРµ РІР»РµР·Р»Рё РІ Р±СЋРґР¶РµС‚ вЂ” РІС‹РґРµР»РёС‚Рµ РјРµРЅСЊС€Рµ)\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=PlanContextBuilderTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/gigameter/jmeter/ai/utils/PlanContextBuilder.java src/test/java/org/gigameter/jmeter/ai/utils/PlanContextBuilderTest.java
git commit -m "feat: two-layer context assembly with budget guard"
```

---

### Task 5: Wire builder into `AiChatPanel` + selection-aware session re-send + config

**Files:**
- Modify: `src/main/java/org/gigameter/jmeter/ai/gui/AiChatPanel.java` (`currentTreeContext()` ~1777, `buildCliSessionTurn()` ~1801; add `selectedNodeIds`)
- Modify: `jmeter-ai-sample.properties`
- Test: `src/test/java/org/gigameter/jmeter/ai/gui/AiChatPanelSelectionContextTest.java`

**Interfaces:**
- Consumes: `PlanContextBuilder.build`, `PlanContextBuilder.selectionHash`, `JMeterPlanSerializer.serialize(root, SKELETON_MAX_ELEMENTS, DEFAULT_MAX_DEPTH)`, `getTreeListener().getSelectedNodes()`.
- Produces (testable seam): `static String AiChatPanel.buildPlanContextForTest(SerializedPlan plan, java.util.List<Integer> selectedIds, int threshold, int maxChars)` delegating to `PlanContextBuilder.build` вЂ” lets the wiring be tested without a live tree.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/gigameter/jmeter/ai/gui/AiChatPanelSelectionContextTest.java`:

```java
package org.gigameter.jmeter.ai.gui;

import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.ElementEntry;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatPanelSelectionContextTest {

    private static ElementEntry e(int id, int depth, String type, String name) {
        return new ElementEntry(id, depth, type, name, new LinkedHashMap<>());
    }

    private static SerializedPlan plan() {
        List<ElementEntry> l = new ArrayList<>();
        l.add(e(1, 0, "TestPlan", "Plan"));
        l.add(e(2, 1, "ThreadGroup", "TG A"));
        l.add(e(3, 2, "HTTPSamplerProxy", "GET /a"));
        return new SerializedPlan(l, new LinkedHashMap<>(), false);
    }

    @Test
    void buildsSkeletonWithoutSelection() {
        String out = AiChatPanel.buildPlanContextForTest(plan(), Collections.emptyList(), 3, 100000);
        assertTrue(out.contains("РЎРўР РЈРљРўРЈР Рђ РџР›РђРќРђ"));
    }

    @Test
    void buildsDetailForSelection() {
        String out = AiChatPanel.buildPlanContextForTest(plan(), Arrays.asList(2), 3, 100000);
        assertTrue(out.contains("Р”Р•РўРђР›Р Р’Р«Р”Р•Р›Р•РќРќР«РҐ Р’Р•РўРћРљ"));
        assertTrue(out.contains("GET /a"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=AiChatPanelSelectionContextTest`
Expected: FAIL вЂ” `buildPlanContextForTest` not defined (compile error).

- [ ] **Step 3: Add the testable seam and wire `currentTreeContext`**

In `AiChatPanel.java`, add imports near the other `utils` imports:

```java
import org.gigameter.jmeter.ai.utils.PlanContextBuilder;
import org.gigameter.jmeter.ai.utils.JMeterPlanSerializer.SerializedPlan;
```

Add the test seam (place near other `*ForTest` helpers):

```java
    /** Test seam: assemble the two-layer plan context from a prebuilt plan + selected ids. */
    static String buildPlanContextForTest(SerializedPlan plan, java.util.List<Integer> selectedIds,
                                          int threshold, int maxChars) {
        return PlanContextBuilder.build(plan, selectedIds, threshold, maxChars);
    }
```

Replace the body of `currentTreeContext()` so it builds the skeleton-capacity plan, reads the selection, and calls the builder:

```java
    private String[] currentTreeContext() {
        String tree = "";
        String revision = null;
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp != null && gp.getTreeModel() != null
                    && gp.getTreeModel().getRoot() instanceof JMeterTreeNode) {
                JMeterTreeNode root = JMeterPlanSerializer.planRoot(
                        (JMeterTreeNode) gp.getTreeModel().getRoot());
                SerializedPlan plan = JMeterPlanSerializer.serialize(
                        root, JMeterPlanSerializer.SKELETON_MAX_ELEMENTS,
                        JMeterPlanSerializer.DEFAULT_MAX_DEPTH);
                java.util.List<Integer> selected = selectedNodeIds(plan, gp);
                int threshold = Integer.parseInt(
                        AiConfig.getProperty("gigameter.context.collapse.threshold", "3"));
                int maxChars = Integer.parseInt(
                        AiConfig.getProperty("gigameter.context.max.chars", "24000"));
                tree = PlanContextBuilder.build(plan, selected, threshold, maxChars);
                revision = plan.revisionHash() + "#" + PlanContextBuilder.selectionHash(selected);
                log.info("CLI tree context (revision={}):\n{}", revision, tree);
            }
        } catch (Exception e) {
            log.debug("Failed to build CLI tree context", e);
        }
        return new String[] {tree, revision};
    }

    /** Whole-plan #ids of the currently mouse-selected nodes (identity match against nodeById). */
    private java.util.List<Integer> selectedNodeIds(SerializedPlan plan, GuiPackage gp) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        try {
            JMeterTreeNode[] selected = gp.getTreeListener().getSelectedNodes();
            if (selected == null) {
                return ids;
            }
            for (JMeterTreeNode sel : selected) {
                for (java.util.Map.Entry<Integer, JMeterTreeNode> en : plan.nodeById.entrySet()) {
                    if (en.getValue() == sel) {
                        ids.add(en.getKey());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read selected nodes", e);
        }
        return ids;
    }
```

Note: `revision` now folds in the selection hash, so `buildCliSessionTurn()` already re-sends context when the selection changes (it compares the whole `revision` string) вЂ” no further change needed there. Verify `buildCliSessionTurn()` compares the full `ctx[1]` string against `lastSentTreeRevision`; it does.

- [ ] **Step 4: Run test to verify it passes**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test -Dtest=AiChatPanelSelectionContextTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Document the new properties**

In `jmeter-ai-sample.properties`, add under the CLI providers section (after the `gigameter.skills.dir` block near the top, or at the end of the CLI section):

```properties
# -----------------------------
# Agent context for large plans
# -----------------------------
# The plugin sends the agent a compact whole-plan "skeleton" (every element, prop-light, with
# structurally-identical sibling groups collapsed to a representative + "в‰Ў #id" refs) plus the FULL
# detail of the subtrees you have selected with the mouse. This keeps huge legacy plans (dozens of
# thread groups) from being truncated while still giving depth where you are working.
#
# Collapse only triggers for >= this many structurally-identical siblings (by subtree fingerprint,
# never by name вЂ” same-name different-content elements are never collapsed).
gigameter.context.collapse.threshold=3
# Soft char budget for the assembled context. Skeleton (breadth) is always included; selected-subtree
# detail is added until the budget is reached, and anything dropped is announced in-context (never
# silently). Raise if your model has a large context window.
gigameter.context.max.chars=24000
```

- [ ] **Step 6: Run the full suite + commit**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test`
Expected: BUILD SUCCESS, all tests pass (previous 123 + the new ones).

```bash
git add src/main/java/org/gigameter/jmeter/ai/gui/AiChatPanel.java jmeter-ai-sample.properties src/test/java/org/gigameter/jmeter/ai/gui/AiChatPanelSelectionContextTest.java
git commit -m "feat: use two-layer selection-aware plan context in AiChatPanel"
```

---

### Task 6: Full-suite verification + package smoke

**Files:** none (verification only).

- [ ] **Step 1: Run the full offline test suite**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o test`
Expected: `BUILD SUCCESS`; `Tests run: <123 + new>, Failures: 0, Errors: 0`.

- [ ] **Step 2: Build the shaded jar offline**

Run: `& "F:\Coding\tools\apache-maven-3.9.12\bin\mvn.cmd" -o -DskipTests package`
Expected: `BUILD SUCCESS`; `target/jmeter-agent-0.5.0-beta.jar` produced.

- [ ] **Step 3: Manual check note (no code)**

Record in the PR/commit body that end-to-end GUI behavior (large plan в†’ skeleton + selected-subtree detail, no truncation) must be clicked through once in JMeter with the deployed jar, since the selection read (`getSelectedNodes()`) is only exercisable live.

---

## Self-Review

**Spec coverage:**
- Skeleton (whole plan, no element-count truncation) в†’ Task 5 serializes with `SKELETON_MAX_ELEMENTS`; Task 3 renders every element. вњ“
- Structural fingerprint + sibling collapse (by structure, not name; threshold N) в†’ Tasks 2, 3. вњ“
- Representation A (representative + `в‰Ў #id`, ids preserved) в†’ Task 3. вњ“
- Selected-subtree detail via `getSelectedNodes()`, deduped against skeleton, topmost-only в†’ Tasks 3 (expanded marker), 4 (`topmostSelected`), 5 (wiring). вњ“
- Budget guard, breadth-first, visible degradation в†’ Task 4. вњ“
- Prompt format sections в†’ Task 4. вњ“
- Session re-send key `revision + selectionHash` в†’ Task 5. вњ“
- Edge: same-name different-content not collapsed в†’ Tasks 2, 3 tests. вњ“
- Config knobs documented в†’ Task 5. вњ“
- Stage 2 (`get_subtree`) explicitly out of scope в†’ not planned. вњ“
- Tests alongside existing context test в†’ all tasks. вњ“

**Placeholder scan:** No TBD/TODO; all code shown; the one tuning value (`max.chars=24000`) is a concrete default. вњ“

**Type consistency:** `subtreeEnd(List,int)`, `subtreeHashes(List)в†’Map<Integer,String>`, `render(List,int,Set<Integer>)`, `topmostSelected(List,Collection<Integer>)в†’List<Integer>`, `selectionHash(Collection<Integer>)в†’String`, `build(SerializedPlan,Collection<Integer>,int,int)в†’String`, `buildPlanContextForTest(SerializedPlan,List<Integer>,int,int)` вЂ” used consistently across tasks. `SerializedPlan` constructor `(List,Map,boolean)` matches existing signature. вњ“
