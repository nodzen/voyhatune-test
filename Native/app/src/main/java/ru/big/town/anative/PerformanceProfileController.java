package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.media.AudioManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Staged, reversible implementation of the balanced performance profile. */
final class PerformanceProfileController {
    static final String ACTION_RESULT = "ru.big.town.anative.PERFORMANCE_PROFILE_RESULT";
    private static final String TAG = "$$$ Performance $$$";
    private static final String PREFS = "PerformanceProfile";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VoyahTunePerformance"); thread.setDaemon(true); return thread;
    });

    private PerformanceProfileController() {}

    static void apply(Context context, android.content.BroadcastReceiver.PendingResult pending) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            String status;
            try { status = applyInternal(app); }
            catch (Throwable error) { status = "Ошибка: " + error.getClass().getSimpleName(); }
            publish(app, status);
            pending.finish();
        });
    }

    static void restore(Context context, android.content.BroadcastReceiver.PendingResult pending) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            String status;
            try { status = restoreInternal(app); }
            catch (Throwable error) { status = "Ошибка восстановления: " + error.getClass().getSimpleName(); }
            publish(app, status);
            pending.finish();
        });
    }

    private static String applyInternal(Context app) throws Exception {
        PackageManager pm = app.getPackageManager();
        SharedPreferences saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String recoveryFailure = recoverRequiredPackages(pm, saved);
        if (recoveryFailure != null) {
            return "Профиль не применён: не удалось восстановить " + recoveryFailure;
        }
        HealthSnapshot baseline = HealthSnapshot.capture(app);
        if (!baseline.ready) return "Профиль не применён: базовая проверка системы не пройдена";
        List<String> candidates = new ArrayList<>(PerformancePackagePolicy.candidates());
        Collections.sort(candidates);
        int changed = 0;
        for (String pkg : candidates) {
            if (!PerformancePackagePolicy.mayDisable(pkg) || !installed(pm, pkg)) continue;
            String key = "state." + pkg;
            if (!saved.contains(key)) {
                saved.edit().putInt(key, pm.getApplicationEnabledSetting(pkg)).commit();
            }
            CommandResult result = run("pm", "disable-user", "--user", "0", pkg);
            if (!result.success) {
                restoreInternal(app);
                return "Откат: не удалось отключить " + pkg;
            }
            changed++;
            if (!baseline.matches(app)) {
                restoreInternal(app);
                return "Откат: проверка launcher/Bluetooth/звука/приборки не пройдена";
            }
        }
        saved.edit().putBoolean("applied", true).commit();
        return "Профиль применён: " + changed + " пакетов. Оцените boot-to-launcher и первый запуск до/после.";
    }

    private static String restoreInternal(Context app) throws Exception {
        SharedPreferences saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String recoveryFailure = recoverRequiredPackages(app.getPackageManager(), saved);
        int restored = 0;
        SharedPreferences.Editor editor = saved.edit();
        for (String pkg : PerformancePackagePolicy.candidates()) {
            String key = "state." + pkg;
            if (!saved.contains(key)) continue;
            int previous = saved.getInt(key, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            CommandResult result = restoreState(previous, pkg);
            if (result.success) {
                restored++;
                editor.remove(key);
            }
        }
        editor.putBoolean("applied", false).commit();
        int pending = 0;
        for (String pkg : PerformancePackagePolicy.candidates()) {
            if (saved.contains("state." + pkg)) pending++;
        }
        if (recoveryFailure != null) {
            return "Восстановлено: " + restored + "; не удалось включить " + recoveryFailure;
        }
        return pending == 0 ? "Восстановлено: " + restored + " пакетов"
                : "Восстановлено: " + restored + "; требуют повтора: " + pending;
    }

    private static CommandResult restoreState(int state, String pkg) throws Exception {
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return run("pm", "default-state", "--user", "0", pkg);
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return run("pm", "enable", "--user", "0", pkg);
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return run("pm", "disable", "--user", "0", pkg);
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            return run("pm", "disable-until-used", "--user", "0", pkg);
        }
        return run("pm", "disable-user", "--user", "0", pkg);
    }

    private static String recoverRequiredPackages(PackageManager pm, SharedPreferences saved)
            throws Exception {
        for (String pkg : PerformancePackagePolicy.requiredEnabledPackages()) {
            if (!installed(pm, pkg)) continue;
            int state = pm.getApplicationEnabledSetting(pkg);
            if (PerformancePackagePolicy.needsEnableRecovery(pkg, state)) {
                CommandResult result = run("pm", "enable", "--user", "0", pkg);
                if (!result.success) return pkg;
            }
            // A pre-Full-only build may have persisted TBox as an optional package. Once the
            // package is confirmed enabled, discard that stale rollback state so Restore All can
            // never disable it again.
            saved.edit().remove("state." + pkg).commit();
        }
        return null;
    }

    private static boolean protectedPackagesHealthy(Context app) {
        PackageManager pm = app.getPackageManager();
        List<String> protectedPackages = new ArrayList<>(java.util.Arrays.asList(
                "com.qinggan.app.launcher", "com.android.systemui",
                "com.qinggan.systempolicy", "com.qinggan.carsignal.service",
                "com.qinggan.cluster", "com.android.bluetooth"));
        protectedPackages.addAll(PerformancePackagePolicy.requiredEnabledPackages());
        for (String pkg : protectedPackages) {
            if (installed(pm, pkg)) {
                int state = pm.getApplicationEnabledSetting(pkg);
                if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return false;
            }
        }
        return true;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try { pm.getApplicationInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private static CommandResult run(String... command) throws Exception {
        command[0] = "/system/bin/" + command[0];
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line; while ((line = reader.readLine()) != null) output.append(line).append(' ');
        }
        int exit = process.waitFor();
        Log.i(TAG, java.util.Arrays.toString(command) + " exit=" + exit + " " + output);
        return new CommandResult(exit == 0, output.toString());
    }

    private static void publish(Context app, String status) {
        app.sendBroadcast(new Intent(ACTION_RESULT).setPackage("ru.big.town.restoremode")
                .putExtra("status", status));
    }

    private static final class CommandResult {
        final boolean success; final String output;
        CommandResult(boolean success, String output) { this.success = success; this.output = output; }
    }

    private static final class HealthSnapshot {
        final boolean ready;
        final boolean bluetoothPresent;
        final boolean bluetoothEnabled;
        final int clusterDisplay;

        HealthSnapshot(boolean ready, boolean bluetoothPresent, boolean bluetoothEnabled,
                       int clusterDisplay) {
            this.ready = ready;
            this.bluetoothPresent = bluetoothPresent;
            this.bluetoothEnabled = bluetoothEnabled;
            this.clusterDisplay = clusterDisplay;
        }

        static HealthSnapshot capture(Context app) {
            BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
            AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            int cluster = AppLaunchCoordinator.get(app).findClusterDisplay();
            boolean protectedHealthy = protectedPackagesHealthy(app);
            return new HealthSnapshot(protectedHealthy && audio != null && cluster >= 0,
                    bluetooth != null, bluetooth != null && bluetooth.isEnabled(), cluster);
        }

        boolean matches(Context app) {
            HealthSnapshot current = capture(app);
            return current.ready && current.bluetoothPresent == bluetoothPresent
                    && current.bluetoothEnabled == bluetoothEnabled
                    && current.clusterDisplay == clusterDisplay;
        }
    }
}
