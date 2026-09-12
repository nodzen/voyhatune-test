package ru.big.town.restoremode;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent blacklist of packages that are really frozen by Native.
 *
 * A package in this list is disabled with PackageManager and force-stopped. It is therefore absent
 * from the OEM launcher and cannot run background services until the user removes it from the list.
 * The old hiddenLauncherApps preference is read once for migration, but new writes use only this key.
 */
final class FrozenAppStore {
    static final String KEY = "frozenAppBlacklist";
    static final String ENABLED_KEY = "frozenAppsEnabled";
    private static final String LEGACY_KEY = "hiddenLauncherApps";

    // Radio, phone and vehicle-control components must never be offered as freeze candidates.
    private static final Set<String> NEVER_FREEZE = new HashSet<>(Arrays.asList(
            "android",
            "com.android.car",
            "com.qinggan.app.music",
            "com.qinggan.app.radio",
            "com.pateo.rdsapp",
            "com.qinggan.tuner.service",
            "com.qinggan.media",
            "com.qinggan.audiopolicy.service",
            "com.qinggan.bluetoothphone",
            "com.qinggan.app.launcher",
            "com.qinggan.app.vehicle",
            "com.qinggan.app.vehiclesetting",
            "com.qinggan.app.setting",
            "com.qinggan.systemservice",
            "com.qinggan.systemui",
            "com.qinggan.canbus.service",
            "com.qinggan.carsignal.service",
            "com.qinggan.QGBus",
            "com.qinggan.powermanager",
            "com.qinggan.lastmemory.service",
            "com.qinggan.tbox.service",
            "com.qinggan.perception",
            "com.qinggan.systempolicy",
            "com.qinggan.keymanager.service",
            "com.qinggan.app.thirdscreen",
            "com.qinggan.app.islandapp",
            "com.qinggan.app.instrumentcard",
            "com.qinggan.instrumentcard",
            "com.qinggan.cluster",
            "com.qinggan.camera",
            "com.qinggan.dvr",
            "com.qinggan.sched",
            "com.qinggan.otaservice",
            "com.qinggan.ipkupdateservice"
    ));

    private FrozenAppStore() {}

    static List<String> load(SharedPreferences prefs) {
        String raw = prefs.contains(KEY)
                ? prefs.getString(KEY, "[]")
                : prefs.getString(LEGACY_KEY, "[]");
        return sanitize(decode(raw));
    }

    static void save(SharedPreferences prefs, List<String> packages) {
        prefs.edit()
                .putString(KEY, encode(packages))
                .remove(LEGACY_KEY)
                .apply();
    }

    static boolean isEnabled(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(ENABLED_KEY, false);
    }

    static String snapshotCsv(SharedPreferences prefs) {
        // Keep the user's blacklist saved while the master switch is off, but publish an empty
        // snapshot so Native restores every package previously disabled by this feature.
        return isEnabled(prefs) ? android.text.TextUtils.join(",", load(prefs)) : "";
    }

    static boolean isNeverFreeze(String pkg) {
        return pkg != null && NEVER_FREEZE.contains(pkg.trim());
    }

    static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.indexOf('.') > 0 && pkg.matches("[A-Za-z0-9_.]+")
                && !"android".equals(pkg);
    }

    static List<String> decode(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < values.length(); i++) {
                String pkg = values.optString(i, "").trim();
                if (!pkg.isEmpty() && !out.contains(pkg)) out.add(pkg);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static String encode(List<String> packages) {
        JSONArray values = new JSONArray();
        for (String pkg : sanitize(packages)) values.put(pkg);
        return values.toString();
    }

    static List<String> sanitize(List<String> packages) {
        List<String> out = new ArrayList<>();
        if (packages == null) return out;
        for (String pkg : packages) {
            if (pkg == null) continue;
            String normalized = pkg.trim();
            if (!isValidPackageName(normalized)
                    || isNeverFreeze(normalized)
                    || out.contains(normalized)) continue;
            out.add(normalized);
        }
        return out;
    }
}
