package ru.big.town.anative;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persists the last user-launched window/session so it can be assigned to any action picker.
 *
 * <p>The record deliberately lives in NativePrefs: it survives a RestoreMode process restart and
 * does not depend on the launcher still having a task. Restoring a record never writes it back,
 * otherwise invoking "Последняя сессия" would turn the restore operation into a new session.</p>
 */
final class LastSessionStore {
    private static final String TAG = "$$$ LastSessionStore $$$";
    private static final String PREFS = "NativePrefs";
    private static final String TYPE = "voyahtune_last_session_type";
    private static final String UPDATED = "voyahtune_last_session_updated";

    private LastSessionStore() {}

    static void recordSingleHost(Context context, String pkg, int dpi, int displayId) {
        if (context == null || !validPackage(pkg)) return;
        prefs(context).edit()
                .putString(TYPE, "single_host")
                .putString("voyahtune_last_session_pkg", pkg)
                .putInt("voyahtune_last_session_dpi", Math.max(0, dpi))
                .putInt("voyahtune_last_session_display", displayId == 1 ? 1 : 0)
                .putLong(UPDATED, System.currentTimeMillis())
                .apply();
    }

    static void recordFreeform(Context context, String pkg, int displayId) {
        if (context == null || !validPackage(pkg)) return;
        prefs(context).edit()
                .putString(TYPE, "freeform")
                .putString("voyahtune_last_session_pkg", pkg)
                .putInt("voyahtune_last_session_display", displayId == 1 ? 1 : 0)
                .putLong(UPDATED, System.currentTimeMillis())
                .apply();
    }

    static void recordSplit(Context context, String left, String right, int ratio,
                            int leftDpi, int rightDpi, boolean resizable, float split,
                            int presetIdx, String presetId) {
        if (context == null || !validPackage(left) || !validPackage(right)) return;
        prefs(context).edit()
                .putString(TYPE, "split")
                .putString("voyahtune_last_session_left", left)
                .putString("voyahtune_last_session_right", right)
                .putInt("voyahtune_last_session_ratio", ratio)
                .putInt("voyahtune_last_session_left_dpi", Math.max(0, leftDpi))
                .putInt("voyahtune_last_session_right_dpi", Math.max(0, rightDpi))
                .putBoolean("voyahtune_last_session_resizable", resizable)
                .putFloat("voyahtune_last_session_split", split)
                .putInt("voyahtune_last_session_preset_idx", presetIdx)
                .putString("voyahtune_last_session_preset_id", presetId == null ? "" : presetId)
                .putLong(UPDATED, System.currentTimeMillis())
                .apply();
    }

    static boolean restore(Context context) {
        if (context == null) return false;
        SharedPreferences p = prefs(context);
        String type = p.getString(TYPE, "");
        try {
            if ("split".equals(type)) {
                String left = p.getString("voyahtune_last_session_left", "");
                String right = p.getString("voyahtune_last_session_right", "");
                if (!validPackage(left) || !validPackage(right)) return false;
                SplitHostActivity.launchSplit(context.getApplicationContext(), left, right,
                        p.getInt("voyahtune_last_session_ratio", 1),
                        p.getInt("voyahtune_last_session_left_dpi", 0),
                        p.getInt("voyahtune_last_session_right_dpi", 0),
                        p.getBoolean("voyahtune_last_session_resizable", false),
                        p.getFloat("voyahtune_last_session_split", 0f),
                        p.getInt("voyahtune_last_session_preset_idx", -1),
                        p.getString("voyahtune_last_session_preset_id", ""), false);
                return true;
            }
            String pkg = p.getString("voyahtune_last_session_pkg", "");
            if (!validPackage(pkg)) return false;
            int display = p.getInt("voyahtune_last_session_display", 0);
            if ("single_host".equals(type)) {
                SplitHostActivity.launchSingle(context.getApplicationContext(), pkg,
                        p.getInt("voyahtune_last_session_dpi", 0), display, false);
                return true;
            }
            if ("freeform".equals(type)) {
                SetModesReceiverDynamic.openFreeformApp(context.getApplicationContext(), pkg,
                        display, false);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "restore failed: " + e.getMessage());
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validPackage(String pkg) {
        return pkg != null && !pkg.trim().isEmpty() && pkg.indexOf(',') < 0
                && pkg.indexOf('|') < 0 && pkg.indexOf(' ') < 0;
    }
}
