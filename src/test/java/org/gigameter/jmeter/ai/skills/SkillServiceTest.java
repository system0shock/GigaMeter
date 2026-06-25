package org.gigameter.jmeter.ai.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillServiceTest {

    @Test
    void knownSkillsRecognised() {
        assertTrue(SkillService.isSkill("lint"));
        assertTrue(SkillService.isSkill("plan"));
        assertTrue(SkillService.isSkill("optimize"));
        assertTrue(SkillService.isSkill("LINT")); // case-insensitive
    }

    @Test
    void unknownSkillsRejected() {
        assertFalse(SkillService.isSkill("frobnicate"));
        assertFalse(SkillService.isSkill(null));
        assertFalse(SkillService.isSkill(""));
    }

    @Test
    void lintAndPlanMutateOptimizeDoesNot() {
        assertTrue(SkillService.isMutating("lint"));
        assertTrue(SkillService.isMutating("plan"));
        assertFalse(SkillService.isMutating("optimize"));
        assertFalse(SkillService.isMutating(null));
    }
}
