package ru.big.town.restoremode;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;
import java.util.Locale;

/** Owns only the live state and commands displayed by the first settings section. */
final class QuickActionsController implements AutoCloseable {
    private final Activity activity;
    private final View root;
    private final SharedPreferences prefs;
    private final NativeServiceClient nativeService;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final TextView tripTimer;
    private final TextView tripStatus;
    private final TextView vehicleStatus;
    private final Button batteryButton;
    private final GridLayout launchGrid;

    private boolean started;
    private boolean tripActive;
    private boolean tripInDrive;
    private long tripAccumulatedMs;
    private long tripDriveStartElapsed;
    private String tripsJson = "[]";
    private String powerHoldText = "состояние неизвестно";
    private String batteryText = "нет данных";

    private final Runnable tripTick = new Runnable() {
        @Override public void run() {
            renderTrip();
            if (started) main.postDelayed(this, 1_000L);
        }
    };

    private final BroadcastReceiver tripReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            tripActive = intent.getBooleanExtra("tripActive", false);
            tripInDrive = intent.getBooleanExtra("inDrive", false);
            tripAccumulatedMs = intent.getLongExtra("accumMs", 0L);
            tripDriveStartElapsed = intent.getLongExtra("driveStartElapsed", 0L);
            String value = intent.getStringExtra("tripsJson");
            if (value != null) tripsJson = value;
            renderTrip();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int temp = intent.getIntExtra("ambientTemp", -9999);
            int phase = intent.getIntExtra("activationPhase", 0);
            int failure = intent.getIntExtra("failReason", 0);
            String temperature = temp == -9999 || temp == Integer.MIN_VALUE ? "—" : temp + " °C";
            if (failure > 0) {
                batteryText = "ошибка: " + batteryFailure(failure) + ", за бортом " + temperature;
            } else {
                batteryText = batteryPhase(phase) + ", за бортом " + temperature;
            }
            batteryButton.setEnabled(phase == 0);
            batteryButton.setAlpha(phase == 0 ? 1f : 0.55f);
            renderVehicleStatus();
        }
    };

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra("status", 0);
            powerHoldText = powerStatus(status, intent.getIntExtra("exitReason", 0));
            renderVehicleStatus();
            int outcome = intent.getIntExtra("requestOutcome", 0);
            if (outcome != 0) show(powerOutcome(outcome));
        }
    };

    QuickActionsController(Activity activity, View root, SharedPreferences prefs,
                           NativeServiceClient nativeService) {
        this.activity = activity;
        this.root = root;
        this.prefs = prefs;
        this.nativeService = nativeService;
        tripTimer = root.findViewById(R.id.quickTripTimer);
        tripStatus = root.findViewById(R.id.quickTripStatus);
        vehicleStatus = root.findViewById(R.id.quickVehicleStatus);
        batteryButton = root.findViewById(R.id.quickBatteryHeat);
        launchGrid = root.findViewById(R.id.quickLaunchGrid);

        root.findViewById(R.id.quickTripHistory).setOnClickListener(v -> openTripHistory());
        root.findViewById(R.id.quickTripReset).setOnClickListener(v -> confirmTripReset());
        root.findViewById(R.id.quickPowerHold).setOnClickListener(v -> confirmPowerHold());
        root.findViewById(R.id.quickWashMode).setOnClickListener(v -> confirmWashMode());
        batteryButton.setOnClickListener(v -> activateBatteryHeat());
        renderLaunchTiles();
        renderTrip();
        renderVehicleStatus();
    }

    void start() {
        if (started) return;
        started = true;
        activity.registerReceiver(tripReceiver,
                new IntentFilter(QuickActionsContract.TRIP_UPDATE), Context.RECEIVER_EXPORTED);
        activity.registerReceiver(batteryReceiver,
                new IntentFilter(QuickActionsContract.BATTERY_UPDATE), Context.RECEIVER_EXPORTED);
        activity.registerReceiver(powerReceiver,
                new IntentFilter(QuickActionsContract.POWER_HOLD_UPDATE),
                QuickActionsContract.NATIVE_PERMISSION, null, Context.RECEIVER_EXPORTED);
        activity.sendBroadcast(new Intent(QuickActionsContract.TRIP_REQUEST)
                .setPackage(QuickActionsContract.NATIVE_PACKAGE));
        activity.sendBroadcast(new Intent(QuickActionsContract.BATTERY_REQUEST)
                .setPackage(QuickActionsContract.NATIVE_PACKAGE));
        activity.sendBroadcast(new Intent(QuickActionsContract.POWER_HOLD_REQUEST)
                        .setPackage(QuickActionsContract.NATIVE_PACKAGE),
                QuickActionsContract.NATIVE_PERMISSION);
        renderLaunchTiles();
        main.post(tripTick);
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(tripTick);
        try { activity.unregisterReceiver(tripReceiver); } catch (RuntimeException ignored) {}
        try { activity.unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) {}
        try { activity.unregisterReceiver(powerReceiver); } catch (RuntimeException ignored) {}
    }

    void refresh() {
        renderLaunchTiles();
    }

    private void renderTrip() {
        long elapsed = tripAccumulatedMs;
        if (tripActive && tripInDrive) elapsed += SystemClock.elapsedRealtime() - tripDriveStartElapsed;
        long seconds = Math.max(0L, elapsed / 1_000L);
        tripTimer.setText(String.format(Locale.US, "%d:%02d:%02d",
                seconds / 3_600L, (seconds % 3_600L) / 60L, seconds % 60L));
        tripStatus.setText(!tripActive ? "нет активной поездки"
                : tripInDrive ? "в пути" : "на паузе (не Drive)");
    }

    private void renderVehicleStatus() {
        vehicleStatus.setText("Power Hold: " + powerHoldText + "\nПрогрев батареи: " + batteryText);
    }

    private void renderLaunchTiles() {
        launchGrid.removeAllViews();
        int index = 0;
        for (SplitStore.Preset preset : SplitStore.load(prefs)) {
            if (!preset.ready()) continue;
            View tile = LayoutInflater.from(activity).inflate(
                    R.layout.item_split_tile, launchGrid, false);
            try {
                ((android.widget.ImageView) tile.findViewById(R.id.tileIcoLeft))
                        .setImageDrawable(activity.getPackageManager().getApplicationIcon(preset.l));
                ((android.widget.ImageView) tile.findViewById(R.id.tileIcoRight))
                        .setImageDrawable(activity.getPackageManager().getApplicationIcon(preset.r));
            } catch (PackageManager.NameNotFoundException ignored) {}
            ((TextView) tile.findViewById(R.id.tileTitle)).setText(preset.ll + " | " + preset.rl);
            ((TextView) tile.findViewById(R.id.tileRatio)).setText(
                    SplitStore.RATIO_LABELS[Math.max(0, Math.min(4, preset.ratio))]);
            ((TextView) tile.findViewById(R.id.tileState)).setText("");
            tile.setOnClickListener(v -> launchSplit(preset));
            addTile(tile, index++);
        }
        for (String pkg : AppShortcutStore.load(prefs)) {
            View tile = LayoutInflater.from(activity).inflate(R.layout.item_app_tile, launchGrid, false);
            TextView title = tile.findViewById(R.id.tileTitle);
            String label = pkg;
            try {
                ApplicationInfo info = activity.getPackageManager().getApplicationInfo(pkg, 0);
                label = activity.getPackageManager().getApplicationLabel(info).toString();
                ((android.widget.ImageView) tile.findViewById(R.id.tileIco))
                        .setImageDrawable(activity.getPackageManager().getApplicationIcon(info));
            } catch (PackageManager.NameNotFoundException ignored) {}
            title.setText(label);
            tile.setOnClickListener(v -> launchApp(pkg));
            addTile(tile, index++);
        }
        if (index == 0) {
            TextView empty = new TextView(activity);
            empty.setText("Добавьте приложения или split-пресеты в разделе «Приложения и разделение экрана»");
            empty.setTextColor(0xff9aa0aa);
            empty.setTextSize(17f);
            empty.setPadding(10, 14, 10, 14);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.columnSpec = GridLayout.spec(0, 4);
            params.width = 0;
            empty.setLayoutParams(params);
            launchGrid.addView(empty);
        }
    }

    private void addTile(View tile, int index) {
        float density = activity.getResources().getDisplayMetrics().density;
        int margin = Math.round(5f * density);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = Math.round(140f * density);
        params.columnSpec = GridLayout.spec(index % 4, 1, 1f);
        params.rowSpec = GridLayout.spec(index / 4);
        params.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(params);
        launchGrid.addView(tile);
    }

    private void launchApp(String pkg) {
        Bundle data = new Bundle();
        data.putString("left", pkg);
        data.putString("right", "");
        data.putInt("leftDpi", AppDpiStore.get(prefs, pkg));
        data.putInt("rightDpi", 0);
        if (!nativeService.send(QuickActionsContract.MSG_OPEN_APP_OR_SPLIT, 1, data, null)) {
            show("Сервис не готов");
        }
    }

    private void launchSplit(SplitStore.Preset preset) {
        Bundle data = new Bundle();
        data.putString("left", preset.l);
        data.putString("right", preset.r);
        data.putInt("leftDpi", AppDpiStore.get(prefs, preset.l));
        data.putInt("rightDpi", AppDpiStore.get(prefs, preset.r));
        data.putBoolean("resizable", preset.resizable
                && SplitStore.isInteractiveDividerEnabled(prefs));
        data.putFloat("split", SplitStore.leftFraction(preset));
        data.putInt("presetIdx", presetIndex(preset));
        data.putString("presetId", preset.id);
        if (!nativeService.send(QuickActionsContract.MSG_OPEN_APP_OR_SPLIT,
                preset.ratio, data, null)) show("Сервис не готов");
    }

    private int presetIndex(SplitStore.Preset target) {
        List<SplitStore.Preset> values = SplitStore.load(prefs);
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).id.equals(target.id)) return i;
        }
        return -1;
    }

    private void openTripHistory() {
        activity.startActivity(new Intent(activity, TripHistoryActivity.class)
                .putExtra("tripsJson", tripsJson));
    }

    private void confirmTripReset() {
        new MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle("Сбросить таймер")
                .setMessage("Обнулить время текущей поездки? Действие не пишется в историю.")
                .setPositiveButton("Сбросить", (dialog, which) -> activity.sendBroadcast(
                        new Intent(QuickActionsContract.TRIP_RESET)
                                .setPackage(QuickActionsContract.NATIVE_PACKAGE)))
                .setNegativeButton("Отмена", null).show();
    }

    private void confirmPowerHold() {
        new MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle(R.string.power_hold_title)
                .setMessage(R.string.power_hold_confirmation)
                .setPositiveButton(R.string.power_hold_activate, (dialog, which) -> {
                    if (!nativeService.send(QuickActionsContract.MSG_POWER_HOLD)) show("Сервис не готов");
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmWashMode() {
        new MaterialAlertDialogBuilder(activity, R.style.DarkDialog)
                .setTitle(R.string.wash_mode_title)
                .setMessage(R.string.wash_mode_confirmation)
                .setPositiveButton(R.string.wash_mode_activate, (dialog, which) -> {
                    if (!nativeService.send(QuickActionsContract.MSG_WASH_MODE)) show("Сервис не готов");
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void activateBatteryHeat() {
        activity.sendBroadcast(new Intent(QuickActionsContract.BATTERY_ACTIVATE)
                .setPackage(QuickActionsContract.NATIVE_PACKAGE));
        show("Запрос отправлен, ожидаем подтверждение автомобиля…");
    }

    private void show(String text) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show();
    }

    private static String powerStatus(int status, int exitReason) {
        if (status == 1 && exitReason == 1) return "завершён: низкий заряд";
        if (status == 1 && exitReason == 2) return "завершён по времени";
        switch (status) {
            case 1: return "не активен";
            case 2: return "активация…";
            case 3: return "активен";
            case 4: return "ошибка";
            default: return "состояние неизвестно";
        }
    }

    private static String powerOutcome(int outcome) {
        switch (outcome) {
            case 1: return "Power Hold принят автомобилем";
            case 2: return "Power Hold доступен только в Parking";
            case 3: return "Недостаточный заряд батареи";
            case 4: return "Состояние автомобиля пока недоступно";
            default: return "Автомобиль не подтвердил Power Hold";
        }
    }

    private static String batteryPhase(int phase) {
        switch (phase) {
            case 1: return "отправка команды";
            case 2: return "ожидание подтверждения";
            case 3: return "прогрев активен";
            case 4: return "сейчас недоступен";
            case 5: return "контроль включён";
            default: return "ожидание команды";
        }
    }

    private static String batteryFailure(int failure) {
        switch (failure) {
            case 1: return "идёт зарядка";
            case 2: return "высоковольтная сеть выключена";
            case 3: return "низкий заряд";
            case 4: return "температура вне диапазона";
            default: return "автомобиль отклонил команду";
        }
    }

    @Override public void close() {
        stop();
    }
}
