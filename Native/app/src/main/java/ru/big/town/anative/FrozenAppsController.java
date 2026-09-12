package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Applies the persistent freeze blacklist. A frozen package is force-stopped and disabled at the
 * PackageManager level, so its launcher activities, services and receivers cannot run. Removing a
 * package from the blacklist restores the enabled state. No APK or application data is deleted.
 */
final class FrozenAppsController {
    private static final String TAG = "$$$ FrozenAppsController $$$";
    private static final int FIRST_APPLICATION_UID = Process.FIRST_APPLICATION_UID;
    private static final String PREVIOUS_STATES_KEY = "voyahtune_frozen_previous_states";

    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
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
            "com.qinggan.ipkupdateservice",
            "ru.big.town.anative",
            "ru.big.town.restoremode"
    ));

    // These OEM system-uid packages were observed as optional UI/media extras on this head unit.
    // Other system-uid packages are rejected even if a malformed config tries to include them.
    private static final Set<String> OPTIONAL_SYSTEM_UID = new HashSet<>(Arrays.asList(
            "com.qinggan.app.dab",
            "com.adayo.service.dab",
            "com.qinggan.dab",
            "com.qinggan.app.hiboard",
            "com.qinggan.app.video",
            "com.qinggan.app.qscene",
            "com.qinggan.app.gallery",
            "com.qinggan.app.factorytest",
            "com.qinggan.app.campmode",
            "com.qinggan.app.restmode"
    ));

    private FrozenAppsController() {}

    static String normalizeCsv(String csv) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                String pkg = raw == null ? "" : raw.trim();
                if (isValidPackageName(pkg) && !isProtected(pkg)) packages.add(pkg);
            }
        }
        return android.text.TextUtils.join(",", packages);
    }

    static void apply(Context context, String previousCsv, String nextCsv) {
        if (context == null) return;
        Set<String> previous = parse(previousCsv);
        Set<String> next = parse(nextCsv);
        PackageManager pm = context.getPackageManager();
        Map<String, Integer> originalStates = readOriginalStates(context);

        for (String pkg : previous) {
            if (!next.contains(pkg)) {
                Integer original = originalStates.remove(pkg);
                restore(pm, pkg, original);
            }
        }
        for (String pkg : next) {
            if (!originalStates.containsKey(pkg)) {
                try {
                    originalStates.put(pkg, pm.getApplicationEnabledSetting(pkg));
                } catch (Exception e) {
                    // DEFAULT is the safest fallback for packages first seen before the state
                    // snapshot could be persisted; the package is still validated in freeze().
                    originalStates.put(pkg, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                    Log.w(TAG, "cannot read enabled state for " + pkg + ": " + rootCause(e));
                }
            }
            freeze(context, pm, pkg);
        }
        writeOriginalStates(context, originalStates);
    }

    static boolean isProtected(String pkg) {
        return pkg != null && PROTECTED.contains(pkg.trim());
    }

    static boolean isAllowedCandidate(ApplicationInfo info) {
        if (info == null || info.packageName == null) return false;
        String pkg = info.packageName;
        if (isProtected(pkg) || !isValidPackageName(pkg)) return false;
        if (pkg.startsWith("com.android.") || pkg.startsWith("android.")) return false;
        if ((info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return false;
        return info.uid >= FIRST_APPLICATION_UID || OPTIONAL_SYSTEM_UID.contains(pkg);
    }

    private static Set<String> parse(String csv) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (csv == null) return result;
        for (String raw : csv.split(",")) {
            String pkg = raw == null ? "" : raw.trim();
            if (isValidPackageName(pkg) && !isProtected(pkg)) result.add(pkg);
        }
        return result;
    }

    private static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.indexOf('.') > 0 && pkg.matches("[A-Za-z0-9_.]+")
                && !"android".equals(pkg);
    }

    private static void freeze(Context context, PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (!isAllowedCandidate(info)) {
                Log.w(TAG, "freeze skipped protected/unsafe package: " + pkg);
                return;
            }
            forceStop(context, pkg);
            pm.setApplicationEnabledSetting(pkg,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
            Log.i(TAG, "frozen " + pkg);
        } catch (Exception e) {
            Log.e(TAG, "freeze failed " + pkg + ": " + rootCause(e), e);
        }
    }

    private static void restore(PackageManager pm, String pkg, Integer originalState) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            if (isProtected(pkg) || info == null) return;
            int state = originalState == null
                    ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : originalState;
            if (state < PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || state > PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            pm.setApplicationEnabledSetting(pkg,
                    state,
                    PackageManager.DONT_KILL_APP);
            Log.i(TAG, "restored " + pkg + " enabledState=" + state);
        } catch (Exception e) {
            Log.e(TAG, "restore failed " + pkg + ": " + rootCause(e), e);
        }
    }

    private static Map<String, Integer> readOriginalStates(Context context) {
        Map<String, Integer> states = new LinkedHashMap<>();
        try {
            String raw = android.provider.Settings.Global.getString(
                    context.getContentResolver(), PREVIOUS_STATES_KEY);
            if (raw == null || raw.trim().isEmpty()) return states;
            for (String entry : raw.split(";")) {
                int separator = entry.lastIndexOf('=');
                if (separator <= 0 || separator >= entry.length() - 1) continue;
                String pkg = entry.substring(0, separator).trim();
                int state = Integer.parseInt(entry.substring(separator + 1).trim());
                if (isValidPackageName(pkg)
                        && state >= PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        && state <= PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                    states.put(pkg, state);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read previous enabled states: " + rootCause(e));
        }
        return states;
    }

    private static void writeOriginalStates(Context context, Map<String, Integer> states) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : states.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        android.provider.Settings.Global.putString(
                context.getContentResolver(), PREVIOUS_STATES_KEY, out.toString());
    }

    private static void forceStop(Context context, String pkg) throws Exception {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) throw new IllegalStateException("ActivityManager unavailable");
        Method method = ActivityManager.class.getMethod("forceStopPackage", String.class);
        method.invoke(am, pkg);
    }

    private static Throwable rootCause(Exception e) {
        if (e instanceof InvocationTargetException && e.getCause() != null) return e.getCause();
        return e;
    }
}
