package ru.big.town.anative;

import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Exact allow/deny policy for the reversible balanced profile. */
final class PerformancePackagePolicy {
    private static final Set<String> OPTIONAL = new HashSet<>(Arrays.asList(
            "com.qinggan.otaservice",
            "com.qinggan.ipkupdateservice",
            "com.qinggan.analytics.service",
            "com.qinggan.usersettingtracking",
            "com.qinggan.app.login",
            "com.qinggan.recognition.service",
            "com.qinggan.app.dab",
            "com.adayo.service.dab",
            "com.qinggan.app.video",
            "com.qinggan.remotedebug"
    ));
    private static final String[] NEVER_PARTS = {
            "bluetooth", "audio", "launcher", "systemui", "instrument", "cluster",
            "systempolicy", "canbus", "carsignal", "power", "tbox", "telematics"
    };
    private static final Set<String> REQUIRED_ENABLED = new HashSet<>(Arrays.asList(
            "com.qinggan.tbox.service"
    ));

    private PerformancePackagePolicy() {}

    static boolean mayDisable(String packageName) {
        if (packageName == null || !OPTIONAL.contains(packageName)) return false;
        String lower = packageName.toLowerCase(java.util.Locale.ROOT);
        for (String protectedPart : NEVER_PARTS) if (lower.contains(protectedPart)) return false;
        return true;
    }

    static boolean mustRemainEnabled(String packageName) {
        return packageName != null && REQUIRED_ENABLED.contains(packageName.trim());
    }

    static boolean needsEnableRecovery(String packageName, int enabledState) {
        return mustRemainEnabled(packageName)
                && enabledState != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && enabledState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    static Set<String> candidates() { return new HashSet<>(OPTIONAL); }

    static Set<String> requiredEnabledPackages() { return new HashSet<>(REQUIRED_ENABLED); }
}
