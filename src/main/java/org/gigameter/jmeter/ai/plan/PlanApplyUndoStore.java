package org.gigameter.jmeter.ai.plan;

import org.apache.jmeter.gui.tree.JMeterTreeNode;

/**
 * Stores last @plan apply operation target for simple rollback.
 */
public final class PlanApplyUndoStore {
    private static JMeterTreeNode lastAppliedThreadGroup;

    private PlanApplyUndoStore() {
    }

    public static synchronized void save(JMeterTreeNode threadGroupNode) {
        lastAppliedThreadGroup = threadGroupNode;
    }

    public static synchronized JMeterTreeNode getLastAppliedThreadGroup() {
        return lastAppliedThreadGroup;
    }

    public static synchronized void clear() {
        lastAppliedThreadGroup = null;
    }
}
