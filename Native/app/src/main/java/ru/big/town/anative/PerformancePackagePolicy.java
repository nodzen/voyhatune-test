package ru.big.town.anative;

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

    private PerformancePackagePolicy() {}

    static boolean mayDisable(String packageName) {
        if (packageName == null || !OPTIONAL.contains(packageName)) return false;
        String lower = packageName.toLowerCase(java.util.Locale.ROOT);
        for (String protectedPart : NEVER_PARTS) if (lower.contains(protectedPart)) return false;
        return true;
    }

    static Set<String> candidates() { return new HashSet<>(OPTIONAL); }
}
