package ru.big.town.restoremode;

import org.junit.Test;
import static org.junit.Assert.*;

public class FeatureSettingsMigrationTest {
    @Test public void newInstallIsSafeAndMusicOnly() {
        FeatureSettingsMigration.Snapshot value = FeatureSettingsMigration.migrateSnapshot(
                0, null, false, true);
        assertEquals(FeatureSettingsMigration.CURRENT_SCHEMA, value.schema);
        assertEquals("widgets-v1|music", value.cards);
        assertFalse(value.clusterGestureEnabled);
    }

    @Test public void legacyTripToggleBecomesOrderedCard() {
        FeatureSettingsMigration.Snapshot value = FeatureSettingsMigration.migrateSnapshot(
                1, "widgets-v1|music", true, true);
        assertEquals("widgets-v1|music|trip", value.cards);
        assertTrue(value.clusterGestureEnabled);
    }
}
