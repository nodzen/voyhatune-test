package ru.big.town.restoremode;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered allowlist for cluster launches. */
final class ClusterAppStore {
    private static final String KEY = "clusterAllowedPackages";
    private ClusterAppStore() {}

    static List<String> load(SharedPreferences prefs) {
        return decode(prefs.getString(KEY, ""));
    }

    static void save(SharedPreferences prefs, List<String> packages) {
        prefs.edit().putString(KEY, encode(packages)).apply();
    }

    static String snapshotCsv(SharedPreferences prefs) { return encode(load(prefs)); }

    static List<String> decode(String csv) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (csv != null) for (String raw : csv.split(",")) {
            String pkg = raw.trim();
            if (validPackage(pkg)) values.add(pkg);
        }
        return new ArrayList<>(values);
    }

    static String encode(List<String> packages) {
        StringBuilder out = new StringBuilder();
        if (packages != null) for (String pkg : packages) {
            if (!validPackage(pkg) || contains(out, pkg)) continue;
            if (out.length() > 0) out.append(',');
            out.append(pkg);
        }
        return out.toString();
    }

    private static boolean contains(StringBuilder csv, String value) {
        for (String item : csv.toString().split(",")) if (value.equals(item)) return true;
        return false;
    }

    static boolean validPackage(String value) {
        return value != null && value.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+");
    }
}
