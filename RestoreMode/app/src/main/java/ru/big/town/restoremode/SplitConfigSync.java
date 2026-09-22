package ru.big.town.restoremode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Единая event-driven публикация сохранённой конфигурации в Native. Fullscreen/Dock/steering/DPI/keyboard
 * зеркалируются при изменении, старте и физическом пробуждении без периодического чтения.
 */
final class SplitConfigSync {
    private static final String NATIVE_PKG = "ru.big.town.anative";
    private static final String CONFIG_RECEIVER = "ru.big.town.anative.SetModesConfigReceiver";

    private SplitConfigSync() {}

    static void pushAll(Context context, SharedPreferences prefs) {
        pushFrozenApps(context, prefs);
        pushFullscreenApps(context, prefs);
        pushAppDpi(context, prefs, null, 0);
        pushDock(context, prefs);
        pushSteering(context, prefs);
        pushKeyboard(context, prefs);
        pushHomeWidgets(context, prefs);
        pushClusterWidgets(context, prefs);
    }

    static void pushFrozenApps(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.FROZEN_APPS_CONFIG");
        i.putExtra("packagesCsv", FrozenAppStore.snapshotCsv(prefs));
        context.sendBroadcast(i);
    }

    static void pushFullscreenApps(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.FULLSCREEN_APPS_CONFIG");
        i.putExtra("packagesCsv", FullscreenAppStore.snapshotCsv(prefs));
        context.sendBroadcast(i);
    }

    /** Публикует полный DPI snapshot; changedPkg нужен, чтобы надёжно передать переход в «Авто» (0). */
    static void pushAppDpi(Context context, SharedPreferences prefs, String changedPkg, int changedDpi) {
        Intent i = configIntent("ru.big.town.anative.APP_DPI_CONFIG");
        i.putExtra("appDpiJson", AppDpiStore.snapshotJson(prefs));
        if (changedPkg != null && !changedPkg.isEmpty()) {
            i.putExtra("changedPkg", changedPkg);
            i.putExtra("changedDpi", Math.max(0, changedDpi));
        }
        context.sendBroadcast(i);
    }

    static void pushDock(Context context, SharedPreferences prefs) {
        String p1 = prefs.getString("dockOverride1", "");
        String p2 = prefs.getString("dockOverride2", "");
        Intent i = configIntent("ru.big.town.anative.DOCK_CONFIG");
        i.putExtra("dock1", p1.isEmpty() ? "none" : p1);
        i.putExtra("dock2", p2.isEmpty() ? "none" : p2);
        i.putExtra("dock1Dpi", p1.isEmpty() ? 0 : AppDpiStore.get(prefs, p1));
        i.putExtra("dock2Dpi", p2.isEmpty() ? 0 : AppDpiStore.get(prefs, p2));
        i.putExtra("dock1Long", resolveSteerAction(dockLongAction(1, prefs), prefs));
        i.putExtra("dock2Long", resolveSteerAction(dockLongAction(2, prefs), prefs));
        addDockSplitExtras(i, 1, p1, prefs);
        addDockSplitExtras(i, 2, p2, prefs);
        context.sendBroadcast(i);
    }

    static void pushSteering(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.STEER_CONFIG");
        i.putExtra("steerStarShort", resolveSteerActions(prefs.getString("steerStarShort", "none"), prefs));
        i.putExtra("steerStarLong", resolveSteerActions(prefs.getString("steerStarLong", "none"), prefs));
        i.putExtra("steerDvrShort", resolveSteerActions(prefs.getString("steerDvrShort", "none"), prefs));
        i.putExtra("steerDvrLong", resolveSteerActions(prefs.getString("steerDvrLong", "none"), prefs));
        i.putExtra("steerVoiceShort", resolveSteerActions(prefs.getString("steerVoiceShort", "none"), prefs));
        i.putExtra("steerVoiceLong", resolveSteerActions(prefs.getString("steerVoiceLong", "none"), prefs));
        i.putExtra("steerPhoneShort", resolveSteerActions(prefs.getString("steerPhoneShort", "none"), prefs));
        i.putExtra("steerPhoneLong", resolveSteerActions(prefs.getString("steerPhoneLong", "none"), prefs));
        context.sendBroadcast(i);
    }

    /**
     * Keyboard hooks are full-only and opt-in. Both UI switches are projections of this single
     * mutually-exclusive mode because the English and Russian agents hook the same Qinggan IME
     * methods and must never be injected together.
     */
    static void pushKeyboard(Context context, SharedPreferences prefs) {
        String mode = normalizeKeyboardMode(prefs.getString("keyboardMode", "off"));
        Intent i = configIntent("ru.big.town.anative.KEYBOARD_CONFIG");
        i.putExtra("keyboardMode", mode);
        context.sendBroadcast(i);
    }

    /** Publishes OEM home shelves and the instrument-cluster now-playing toggle. */
    static void pushHomeWidgets(Context context, SharedPreferences prefs) {
        Intent i = configIntent("ru.big.town.anative.HOME_WIDGETS_CONFIG");
        for (String region : HomeWidgetStore.REGIONS) {
            i.putExtra("homeWidgets_" + region,
                    prefs.getString(HomeWidgetStore.prefKey(region), ""));
        }
        i.putExtra("instrumentNowPlaying",
                prefs.getBoolean("showInstrumentNowPlaying", true));
        i.putExtra("homeThirdPartyMedia",
                prefs.getBoolean("homeThirdPartyMedia", true));
        context.sendBroadcast(i);
    }

    static void pushClusterWidgets(Context context, SharedPreferences prefs) {
        FeatureSettingsMigration.migrate(prefs);
        Intent i = configIntent("ru.big.town.anative.CLUSTER_WIDGET_CONFIG");
        i.putExtra("schemaVersion", FeatureSettingsMigration.CURRENT_SCHEMA);
        i.putExtra("clusterGestureEnabled", prefs.getBoolean("clusterGestureEnabled", false));
        i.putExtra("clusterAllowedPackages", ClusterAppStore.snapshotCsv(prefs));
        i.putExtra("widgetCards", CustomWidgetStore.loadEncoded(prefs));
        context.sendBroadcast(i);
    }

    static String normalizeKeyboardMode(String mode) {
        return "en".equals(mode) || "ru".equals(mode) ? mode : "off";
    }

    private static Intent configIntent(String action) {
        Intent i = new Intent(action);
        i.setClassName(NATIVE_PKG, CONFIG_RECEIVER);
        return i;
    }

    private static void addDockSplitExtras(Intent i, int slot, String slotPkg, SharedPreferences prefs) {
        int idx = slotPkg.isEmpty() ? -1 : prefs.getInt("dockOverride" + slot + "Split", -1);
        List<SplitStore.Preset> all = SplitStore.load(prefs);
        if (idx < 0 || idx >= all.size() || !all.get(idx).ready()) {
            i.putExtra("dock" + slot + "HasSplit", false);
            return;
        }
        SplitStore.Preset ps = all.get(idx);
        i.putExtra("dock" + slot + "HasSplit", true);
        i.putExtra("dock" + slot + "SplitL", ps.l);
        i.putExtra("dock" + slot + "SplitR", ps.r);
        i.putExtra("dock" + slot + "SplitRatio", ps.ratio);
        i.putExtra("dock" + slot + "SplitLDpi", AppDpiStore.get(prefs, ps.l));
        i.putExtra("dock" + slot + "SplitRDpi", AppDpiStore.get(prefs, ps.r));
        i.putExtra("dock" + slot + "SplitResizable", ps.resizable
                && SplitStore.isInteractiveDividerEnabled(prefs));
        i.putExtra("dock" + slot + "SplitFraction", SplitStore.leftFraction(ps));
        i.putExtra("dock" + slot + "SplitPresetIdx", idx);       // fallback для старого Native
        i.putExtra("dock" + slot + "SplitPresetId", ps.id);
    }

    /**
     * Долгое действие слота дока. Старые настройки хранили только индекс split-пресета;
     * при отсутствии нового ключа превращаем его в обычное action-значение для Native.
     */
    private static String dockLongAction(int slot, SharedPreferences prefs) {
        String stored = prefs.getString("dockOverride" + slot + "Long", "");
        if (stored != null && !stored.trim().isEmpty()) return stored;
        int idx = prefs.getInt("dockOverride" + slot + "Split", -1);
        List<SplitStore.Preset> all = SplitStore.load(prefs);
        return idx >= 0 && idx < all.size() && all.get(idx).ready()
                ? "split:" + idx : "none";
    }

    static String resolveSteerActions(String stored, SharedPreferences prefs) {
        List<String> resolved = new java.util.ArrayList<>();
        for (String action : SteeringActionStore.decode(stored)) {
            String value = resolveSteerAction(action, prefs);
            if (value != null && !value.isEmpty() && !"none".equals(value)) resolved.add(value);
        }
        return SteeringActionStore.encode(resolved);
    }

    /** Backward-compatible CSV: старый Native прочитает первые пять полей, новый — все восемь. */
    static String resolveSteerAction(String id, SharedPreferences prefs) {
        if (id == null || !id.startsWith("split:")) return id;
        try {
            int n = Integer.parseInt(id.substring("split:".length()));
            List<SplitStore.Preset> all = SplitStore.load(prefs);
            if (n >= 0 && n < all.size() && all.get(n).ready()) {
                SplitStore.Preset ps = all.get(n);
                return "split:" + ps.l + "," + ps.r + "," + ps.ratio + ","
                        + AppDpiStore.get(prefs, ps.l) + "," + AppDpiStore.get(prefs, ps.r) + ","
                        + (ps.resizable && SplitStore.isInteractiveDividerEnabled(prefs) ? "1" : "0")
                        + "," + SplitStore.leftFraction(ps) + "," + ps.id;
            }
        } catch (Exception ignored) {}
        return "none";
    }
}
