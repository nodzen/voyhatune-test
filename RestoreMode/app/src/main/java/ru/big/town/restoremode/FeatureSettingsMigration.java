package ru.big.town.restoremode;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Idempotent schema migration for cluster and custom-card settings. */
final class FeatureSettingsMigration {
    static final int CURRENT_SCHEMA = 2;
    private static final String KEY_SCHEMA = "featureSchemaVersion";
    private FeatureSettingsMigration() {}

    static final class Snapshot {
        final int schema;
        final String cards;
        final boolean clusterGestureEnabled;
        Snapshot(int schema, String cards, boolean clusterGestureEnabled) {
            this.schema = schema; this.cards = cards;
            this.clusterGestureEnabled = clusterGestureEnabled;
        }
    }

    static Snapshot migrateSnapshot(int from, String cards, boolean legacyTripWidget,
                                    boolean clusterGestureEnabled) {
        String nextCards = cards == null || cards.isEmpty()
                ? CustomWidgetStore.PREFIX + "music" : CustomWidgetStore.encode(
                        CustomWidgetStore.decode(cards));
        if (from < 2 && legacyTripWidget) {
            List<CustomWidgetStore.Card> list = new ArrayList<>(CustomWidgetStore.decode(nextCards));
            list.add(new CustomWidgetStore.Card("trip"));
            nextCards = CustomWidgetStore.encode(list);
        }
        return new Snapshot(CURRENT_SCHEMA, nextCards,
                from < 1 ? false : clusterGestureEnabled);
    }

    static void migrate(SharedPreferences prefs) {
        int from = prefs.getInt(KEY_SCHEMA, 0);
        if (from >= CURRENT_SCHEMA) return;
        Snapshot migrated = migrateSnapshot(from, prefs.getString(CustomWidgetStore.KEY, null),
                prefs.getBoolean("legacyTripWidget", false),
                prefs.getBoolean("clusterGestureEnabled", false));
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean("clusterGestureEnabled", migrated.clusterGestureEnabled)
                .putString(CustomWidgetStore.KEY, migrated.cards)
                .remove("legacyTripWidget");
        editor.putInt(KEY_SCHEMA, CURRENT_SCHEMA).apply();
    }
}
