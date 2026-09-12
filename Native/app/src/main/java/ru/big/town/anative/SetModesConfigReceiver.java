package ru.big.town.anative;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Защищённый signature-permission вход для конфигурации из RestoreMode. Launcher/steering hooks
 * используют отдельный публичный SetModesReceiverDynamic, который больше не принимает конфиг.
 */
public class SetModesConfigReceiver extends BroadcastReceiver {
    private static final String TAG = "$$$ SetModesConfig $$$";
    public static final String ACTION_DOOR_MEDIA_PAUSE_CHANGED =
            "ru.big.town.anative.DOOR_MEDIA_PAUSE_CHANGED";
    public static final String ACTION_DOOR_MEDIA_RESUME_CHANGED =
            "ru.big.town.anative.DOOR_MEDIA_RESUME_CHANGED";
    public static final String ACTION_DOOR_MEDIA_ANY_CHANGED =
            "ru.big.town.anative.DOOR_MEDIA_ANY_CHANGED";
    public static final String ACTION_PARKING_HEADLIGHTS_CHANGED =
            "ru.big.town.anative.PARKING_HEADLIGHTS_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_DOOR_MEDIA_PAUSE_CHANGED.equals(action)) {
            applyDoorMediaSetting(context, "pauseMediaOnDoor",
                    intent.getBooleanExtra("enabled", false));
            return;
        }
        if (ACTION_DOOR_MEDIA_RESUME_CHANGED.equals(action)) {
            applyDoorMediaSetting(context, "pauseMediaOnDoorClose",
                    intent.getBooleanExtra("enabled", false));
            return;
        }
        if (ACTION_DOOR_MEDIA_ANY_CHANGED.equals(action)) {
            applyDoorMediaSetting(context, "pauseMediaOnAnyDoor",
                    intent.getBooleanExtra("enabled", false));
            return;
        }
        if (ACTION_PARKING_HEADLIGHTS_CHANGED.equals(action)) {
            applyParkingHeadlightsSetting(context, intent.getBooleanExtra("enabled", false));
            return;
        }
        if ("ru.big.town.anative.HOME_WIDGETS_CONFIG".equals(action)) {
            applyHomeWidgetConfig(context, intent);
            return;
        }
        if (!BuildConfig.IS_FULL) return;
        if ("ru.big.town.anative.STEER_CONFIG".equals(action)) {
            String[] buttons = {"Star", "Dvr", "Voice", "Phone"};
            boolean needsBackService = false;
            for (String button : buttons) {
                String shortKey = "steer" + button + "Short";
                String longKey = "steer" + button + "Long";
                SetModesReceiverDynamic.mirrorSteer(context, intent, shortKey);
                SetModesReceiverDynamic.mirrorSteer(context, intent, longKey);
                needsBackService |= SteeringActionSequence.contains(
                        intent.getStringExtra(shortKey), "system_back");
                needsBackService |= SteeringActionSequence.contains(
                        intent.getStringExtra(longKey), "system_back");
            }
            needsBackService |= SteeringActionSequence.contains(
                    intent.getStringExtra("dock1Long"), "system_back");
            needsBackService |= SteeringActionSequence.contains(
                    intent.getStringExtra("dock2Long"), "system_back");
            BackButtonService.setSteeringBackEnabled(context, needsBackService);
            Log.i(TAG, "STEER_CONFIG зеркалирован");
        } else if ("ru.big.town.anative.DOCK_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorDock(context, intent, 1);
            SetModesReceiverDynamic.mirrorDock(context, intent, 2);
            SetModesReceiverDynamic.clearLegacyPassengerDock(context);
            Intent reload = new Intent("ru.big.town.anative.DOCK_RELOAD");
            reload.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(reload);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "DOCK_CONFIG зеркалирован + reload");
        } else if ("ru.big.town.anative.FROZEN_APPS_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorFrozenApps(context, intent);
            Intent reload = new Intent("ru.big.town.anative.DOCK_RELOAD");
            reload.putExtra("reloadAllApps", true);
            reload.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(reload);
            Log.i(TAG, "FROZEN_APPS_CONFIG применён + All Apps reload");
        } else if ("ru.big.town.anative.FREEFORM_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorFreeform(context, intent);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "FREEFORM_CONFIG зеркалирован + reload");
        } else if ("ru.big.town.anative.FULLSCREEN_APPS_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorFullscreenApps(context, intent);
            Intent reload = new Intent("ru.big.town.anative.DOCK_RELOAD");
            reload.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(reload);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "FULLSCREEN_APPS_CONFIG зеркалирован + dock/window reload");
        } else if ("ru.big.town.anative.APP_DPI_CONFIG".equals(action)) {
            SetModesReceiverDynamic.mirrorAppDpi(context, intent);
            SetModesReceiverDynamic.sendWinReload(context);
            Log.i(TAG, "APP_DPI_CONFIG зеркалирован + reload");
        } else if ("ru.big.town.anative.KEYBOARD_CONFIG".equals(action)) {
            applyKeyboardMode(context, intent.getStringExtra("keyboardMode"));
        }
    }

    /** Applies one door-media setting immediately; the next settings query remains the source-of-truth refresh. */
    private static void applyDoorMediaSetting(Context context, String key, boolean enabled) {
        if (context == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "NativePrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(key, enabled).apply();
        boolean wiperEnabled = prefs.getBoolean("wiperCold", false);
        boolean pauseMedia = prefs.getBoolean("pauseMediaOnDoor", false);
        boolean pauseAnyDoor = prefs.getBoolean("pauseMediaOnAnyDoor", false);
        Intent service = new Intent(context, WiperColdService.class);
        if (pauseMedia || pauseAnyDoor || wiperEnabled) {
            context.startForegroundService(service);
        } else {
            context.stopService(service);
        }
        Log.i(TAG, key + "=" + enabled + "; service consumer wiper=" + wiperEnabled
                + " pause=" + pauseMedia + " anyDoor=" + pauseAnyDoor);
    }

    /** Keeps the light service alive when parking-only control is enabled. */
    private static void applyParkingHeadlightsSetting(Context context, boolean enabled) {
        if (context == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "NativePrefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("headlightsOffInParking", enabled).apply();
        boolean autoLight = prefs.getBoolean("autoLight", false);
        if (enabled || autoLight) {
            Intent service = new Intent(context, LightSensorService.class)
                    .setAction(LightSensorService.ACTION_PARKING_HEADLIGHTS_CHANGED)
                    .putExtra("enabled", enabled);
            context.startForegroundService(service);
        } else {
            context.stopService(new Intent(context, LightSensorService.class));
        }
        Log.i(TAG, "headlightsOffInParking=" + enabled + "; autoLight=" + autoLight);
    }

    private static void applyKeyboardMode(Context context, String requestedMode) {
        String mode = normalizeKeyboardMode(requestedMode);
        String previous = Settings.Global.getString(
                context.getContentResolver(), "voyahtune_keyboard_mode");
        String normalizedPrevious = normalizeKeyboardMode(previous);
        if (!Settings.Global.putString(
                context.getContentResolver(), "voyahtune_keyboard_mode", mode)) {
            Log.e(TAG, "KEYBOARD_CONFIG: Settings.Global write failed");
            return;
        }
        if (mode.equals(normalizedPrevious)) {
            Log.i(TAG, "KEYBOARD_CONFIG unchanged: " + mode);
            return;
        }
        // Hooks are eternalized inside qgime. An exact process restart is the only safe way to
        // remove or replace them; load.bin injects at most once into the new Android 11 process.
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            Method forceStopPackage = ActivityManager.class.getMethod("forceStopPackage", String.class);
            forceStopPackage.invoke(am, "com.qinggan.app.qgime");
            Log.i(TAG, "KEYBOARD_CONFIG=" + mode + "; Qinggan IME restarted");
        } catch (Exception e) {
            Log.e(TAG, "KEYBOARD_CONFIG saved, but Qinggan IME restart failed", e);
        }
    }

    private static void applyHomeWidgetConfig(Context context, Intent intent) {
        android.content.ContentResolver resolver = context.getContentResolver();
        String[] regions = {"left_small", "left_big", "right_small", "right_big"};
        for (String region : regions) {
            String value = intent.getStringExtra("homeWidgets_" + region);
            if (value == null) value = "";
            // The launcher hook accepts only the small operation alphabet and treats empty as
            // factory order. The receiver keeps the transport bounded and never stores arbitrary
            // JSON from the UI process.
            Settings.Global.putString(resolver, "voyahtune_home_widgets_" + region,
                    sanitizeWidgetCsv(value));
        }
        Settings.Global.putInt(resolver, "voyahtune_instrument_now_playing",
                intent.getBooleanExtra("instrumentNowPlaying", true) ? 1 : 0);
        Settings.Global.putInt(resolver, "voyahtune_home_third_party_media",
                intent.getBooleanExtra("homeThirdPartyMedia", true) ? 1 : 0);
        Intent reload = new Intent("ru.big.town.anative.DOCK_RELOAD");
        reload.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        context.sendBroadcast(reload);
        Log.i(TAG, "HOME_WIDGETS_CONFIG применён + launcher/instrument reload");
    }

    private static String sanitizeWidgetCsv(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : value.split(",")) {
            String operation = raw.trim();
            if (!operation.matches("[A-Za-z0-9_]+")) continue;
            if (out.length() > 0) out.append(',');
            out.append(operation);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static String normalizeKeyboardMode(String mode) {
        return "en".equals(mode) || "ru".equals(mode) ? mode : "off";
    }
}
