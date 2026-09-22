package ru.big.town.anative;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskRetirementPolicyTest {
    @Test public void retiresOnlyARealCrossDisplayTransition() {
        assertTrue(TaskRetirementPolicy.shouldRetire("app.maps", 8,
                new SplitHostTaskSnapshot.TaskRecord(1, "app.maps", 0)));
        assertFalse(TaskRetirementPolicy.shouldRetire("app.maps", 8,
                new SplitHostTaskSnapshot.TaskRecord(1, "app.maps", 8)));
        assertFalse(TaskRetirementPolicy.shouldRetire("app.maps", 8,
                new SplitHostTaskSnapshot.TaskRecord(1, "app.maps", null)));
    }
}
