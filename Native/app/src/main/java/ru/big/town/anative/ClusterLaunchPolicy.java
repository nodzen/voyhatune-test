package ru.big.town.anative;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Android-free validation for every cluster launch entry point. */
final class ClusterLaunchPolicy {
    private ClusterLaunchPolicy() {}

    static Set<String> parseAllowlist(String csv) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (csv == null) return result;
        for (String raw : csv.split(",")) {
            String pkg = raw.trim();
            if (isPackageName(pkg)) result.add(pkg);
        }
        return result;
    }

    static String packageFromAction(String action) {
        if (action == null || !action.startsWith("cluster_app:")) return null;
        String pkg = action.substring("cluster_app:".length()).trim();
        return isPackageName(pkg) ? pkg : null;
    }

    static boolean allows(String csv, String packageName) {
        return packageName != null && parseAllowlist(csv).contains(packageName);
    }

    static boolean shouldInterceptGesture(boolean enabled, int sourceDisplayId,
                                          boolean movingLeft, String foregroundPackage,
                                          String allowlistCsv) {
        return enabled && sourceDisplayId == 0 && movingLeft
                && allows(allowlistCsv, foregroundPackage);
    }

    private static boolean isPackageName(String value) {
        if (value == null || value.length() < 3 || value.length() > 220
                || value.startsWith(".") || value.endsWith(".") || value.indexOf('.') < 1) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '.' || c == '_' || Character.isLetterOrDigit(c))) return false;
        }
        return value.toLowerCase(Locale.ROOT).equals(value);
    }
}
