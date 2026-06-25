package org.gigameter.jmeter.ai.service.ops;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Holds the inverse actions for the most recent applied op-batch so the user can roll it back with a
 * single action. Each mutating op records a {@link Runnable} that undoes it; {@link #undoLast()}
 * runs the current batch's inverses in reverse order (must be invoked on the EDT, like the original
 * mutations). Only the latest batch is retained — applying a new batch replaces the buffer.
 */
public final class OpsUndoStore {

    private static final Deque<Runnable> CURRENT = new ArrayDeque<>();
    private static boolean recording;

    private OpsUndoStore() {
    }

    /** Starts a fresh batch, discarding any previously recorded inverses. */
    public static synchronized void beginBatch() {
        CURRENT.clear();
        recording = true;
    }

    /** Records one inverse action for the batch in progress. */
    public static synchronized void record(Runnable inverse) {
        if (recording && inverse != null) {
            CURRENT.push(inverse);
        }
    }

    /** Ends the batch; if nothing was recorded the buffer stays empty. */
    public static synchronized void endBatch() {
        recording = false;
    }

    public static synchronized boolean canUndo() {
        return !CURRENT.isEmpty();
    }

    /**
     * Runs every recorded inverse (most-recent first) and clears the buffer.
     * @return number of inverse actions executed
     */
    public static synchronized int undoLast() {
        int n = 0;
        while (!CURRENT.isEmpty()) {
            Runnable r = CURRENT.pop();
            try {
                r.run();
                n++;
            } catch (RuntimeException e) {
                // Best effort: keep undoing the rest even if one inverse fails.
            }
        }
        return n;
    }

    public static synchronized void clear() {
        CURRENT.clear();
        recording = false;
    }
}
