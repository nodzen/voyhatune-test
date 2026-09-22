package ru.big.town.anative;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** Stateless entry points and the latest persisted vehicle-mode snapshot used by Native services. */
final class VehicleCommandFacade {
    public static String driveMode = "INDIVIDUAL";
    private static String energy = "SREV";
    private static String recycle = "LOW";
    private static String customCommand = "";
    public static int customCommandCount = 1;
    public static String customCommandStarButton1 = "";
    public static String customCommandStarButton2 = "";

    private static boolean driveEnabled   = false;
    private static boolean recycleEnabled = false;
    private static boolean energyEnabled  = false;
    // Each switch is both the restore opt-in and the permission to learn the latest car value.
    private static boolean driveRememberLast = true;
    private static boolean energyRememberLast = true;
    private static boolean recycleRememberLast = true;
    // Kept as a derived compatibility value for older cache/broadcast formats.
    private static boolean rememberModes = true;
    private static boolean headlightsOffInParking = false;
    private static boolean disablePedestrianSound = false;
    /** Форсированный электрорежим (колонка 19 провайдера RestoreMode). */
    private static boolean forcedEv = false;
    private static boolean fragranceEnabled = false;
    private static int fragranceTaste = FragranceRestorePolicy.DEFAULT_TASTE;
    private static int fragranceDuration = FragranceRestorePolicy.DEFAULT_DURATION;
    private static int fragranceIntensity = FragranceRestorePolicy.DEFAULT_INTENSITY;
    private static boolean apolloTlcEnabled = false;
    private static boolean apolloTrafficLightsEnabled = false;
    private static boolean apolloGreenSoundEnabled = false;
    private static boolean apolloTrafficSignsEnabled = false;
    private static boolean apolloStockUiEnabled = false;
    private static boolean pauseMediaOnDoorClose = false;
    private static boolean pauseMediaOnAnyDoor = false;

    //------------- OEM VehicleState-команды режимов энергии ----------------------------------------
    public static byte[][] getCustomCommand() {
        if (customCommand == null || customCommand.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommand.split("\n");
        return CanFrameCodec.parseAll(cmds);
    }

    public static byte[][] getCustomCommandStarButton1() {
        if (customCommandStarButton1 == null || customCommandStarButton1.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommandStarButton1.split("\n");
        return CanFrameCodec.parseAll(cmds);
    }
    public static byte[][] getCustomCommandStarButton2() {
        if (customCommandStarButton2 == null || customCommandStarButton2.isEmpty()) return new byte[][]{{}};
        String[] cmds = customCommandStarButton2.split("\n");
        return CanFrameCodec.parseAll(cmds);
    }

    public static boolean sendEnergyModeCommand(Context context, String mode) {
        return sendOemBundleState(context,
                VehicleRestorePolicy.SOC_MODE, VehicleRestorePolicy.SOC_MODE_ID,
                VehicleRestorePolicy.requireEnergy(mode), "energy mode: " + mode);
    }

    //------------- OEM VehicleState-команды режимов вождения ---------------------------------------
    public static boolean sendDriveModeCommand(Context context, String mode) {
        return DriveModeCanTransport.send(context, mode);
    }

    /** Вариант для совместимости; transport использует общий OEM Binder без фонового retry. */
    public static boolean sendDriveModeCommand(String mode) {
        return DriveModeCanTransport.send(GlobalVars.SAVE_CONTEXT, mode);
    }

    //------------- OEM VehicleState-команды рекуперации --------------------------------------------
    public static boolean sendRecuperationModeCommand(Context context, String mode) {
        if (context == null) return false;
        if (!VehicleRestorePolicy.allowsRecuperationRestore(
                currentSavedMode(context, "driveMode"))) {
            Log.i("$$$ MainActivity recuperation $$$",
                    "Snow owns minimum recuperation; storing selection without CAN send");
            return true;
        }
        return sendOemBundleState(context,
                VehicleRestorePolicy.REGEN_LEVEL, VehicleRestorePolicy.REGEN_LEVEL_ID,
                VehicleRestorePolicy.requireRecycle(mode), "recuperation level: " + mode);
    }

    private static boolean sendOemBundleState(Context context, String name, int stableId,
                                              int value, String label) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put(name, value);
        Map<String, Integer> stableIds = new LinkedHashMap<>();
        stableIds.put(name, stableId);
        return OemVehicleStateTransport.sendBundle(
                context, values, stableIds, label).accepted();
    }

    /** Немедленно применить форсированный EV (тоггл с главного экрана / из настроек). */
    public static boolean sendForcedEvCommand(boolean on) {
        Context context = GlobalVars.SAVE_CONTEXT;
        if (context == null) return false;
        int target = VehicleRestorePolicy.SOC_FORCE_EV;
        if (!on) {
            String savedEnergy = currentSavedMode(context, "energy");
            try {
                target = VehicleRestorePolicy.requireEnergy(savedEnergy);
            } catch (IllegalArgumentException e) {
                Log.w("$$$ MainActivity forced EV $$$",
                        "Invalid saved energy target; falling back to EV", e);
                target = VehicleRestorePolicy.SOC_EV;
            }
        }
        return sendOemBundleState(context,
                VehicleRestorePolicy.SOC_MODE, VehicleRestorePolicy.SOC_MODE_ID, target,
                "forced EV " + (on ? "on" : "off / restore saved energy"));
    }

    //------------- Управление режимом наружного света через штатный CanBusService -------------------
    public static boolean setHeadlights(Context context, boolean on){
        String command = on ? "LOW_BEAM" : "OUT_LAMP_OFF";
        Log.i("$$$ MainActivity setHeadlights $$$", "OEM CAN: " + command);
        if (CanSender.isDebugMode()) {
            Log.i("$$$ MainActivity setHeadlights $$$", "EMULATE OEM TX58: " + command + " state=1");
            return true;
        }
        return HeadlightCanTransport.send(context, on);
    }

    /** Отдельная пара для кнопок руля: ближний свет ↔ штатный автоматический режим. */
    public static boolean setHeadlightsAutoLow(Context context, boolean lowBeam){
        String command = lowBeam ? "LOW_BEAM" : "AUTO_LAMP_SWITCH";
        Log.i("$$$ MainActivity setHeadlights $$$", "OEM CAN: " + command);
        if (CanSender.isDebugMode()) {
            Log.i("$$$ MainActivity setHeadlights $$$", "EMULATE OEM TX58: " + command + " state=1");
            return true;
        }
        return HeadlightCanTransport.sendAutoPair(context, lowBeam);
    }

    public static boolean setCanValues(int cmdNum, byte[][] cmds) {
        return setCanValues(cmdNum, cmds, null);
    }

    public static boolean setCanValues(int cmdNum, byte[][] cmds, String label) {
        //printBytesArrayToLog("$$$ MAIN setCanValues $$$",cmds);
        // Отправка идёт через CanSender: в режиме отладки команды логируются (эмуляция) с меткой,
        // иначе уходят в шину через cis_can_control_bytes.
        return CanSender.send(cmdNum, cmds, label);
    }

    private static final String MODES_LOG = "$$$ MainActivity loadModes";

    /**
     * Загружает настройки режимов. Источник №1 — {@link ru.big.town.restoremode}-провайдер
     * (актуальные значения). Если он ещё не поднят (частый случай сразу после пробуждения),
     * подхватываем последний удачно прочитанный снимок из локального кэша (NativePrefs),
     * чтобы не применять пустые дефолты.
     *
     * @return 2 — прочитаны свежие данные из провайдера;
     *         1 — провайдер недоступен, но применён локальный кэш;
     *         0 — данных нет ни в провайдере, ни в кэше (применять нечего).
     */
    public static int loadModes(Context context) {
        return loadModes(context, true);
    }

    /**
     * @param allowCache false — принимать только свежие данные провайдера (кэш не трогаем);
     *                   используется ApplyEngine в первых попытках, чтобы дать провайдеру
     *                   шанс подняться, прежде чем соглашаться на устаревший снимок.
     */
    public static int loadModes(Context context, boolean allowCache) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(Uri
                            .parse("content://ru.big.town.restoremode.restoremodecontentprovider/"),
                    null, null,
                    null, null);
            if (cursor != null && cursor.getCount() != 0 && cursor.getColumnCount() >= 5) {
                cursor.moveToFirst();
                driveMode = cursor.getString(0);
                energy = cursor.getString(1);
                recycle = cursor.getString(2);
                customCommand = cursor.getString(3);
                customCommandCount = cursor.getInt(4);
                // cols 6,7,8 — старые флаги включения. В новом формате named remember-флаги
                // являются единственным opt-in для конкретного режима.
                boolean legacyDriveEnabled = cursor.getColumnCount() > 6
                        && cursor.getInt(6) == 1;
                boolean legacyRecycleEnabled = cursor.getColumnCount() > 7
                        && cursor.getInt(7) == 1;
                boolean legacyEnergyEnabled = cursor.getColumnCount() > 8
                        && cursor.getInt(8) == 1;
                int driveRememberColumn = cursor.getColumnIndex("driveRememberLast");
                int energyRememberColumn = cursor.getColumnIndex("energyRememberLast");
                int recycleRememberColumn = cursor.getColumnIndex("recycleRememberLast");
                // col 11 — «Отключить звук для пешеходов» (1=отключить, fallback=false)
                disablePedestrianSound = cursor.getColumnCount() > 11 && cursor.getInt(11) == 1;
                forcedEv = cursor.getColumnCount() > 19 && cursor.getInt(19) == 1;
                fragranceEnabled = cursor.getColumnCount() > 20 && cursor.getInt(20) == 1;
                FragranceRestorePolicy.Settings fragrance = FragranceRestorePolicy.normalize(
                        cursor.getColumnCount() > 21 ? cursor.getInt(21)
                                : FragranceRestorePolicy.DEFAULT_TASTE,
                        cursor.getColumnCount() > 22 ? cursor.getInt(22)
                                : FragranceRestorePolicy.DEFAULT_DURATION,
                        cursor.getColumnCount() > 23 ? cursor.getInt(23)
                                : FragranceRestorePolicy.DEFAULT_INTENSITY);
                fragranceTaste = fragrance.taste;
                fragranceDuration = fragrance.duration;
                fragranceIntensity = fragrance.intensity;
                apolloTlcEnabled = cursor.getColumnCount() > 24 && cursor.getInt(24) == 1;
                apolloTrafficLightsEnabled = cursor.getColumnCount() > 25
                        && cursor.getInt(25) == 1;
                apolloGreenSoundEnabled = cursor.getColumnCount() > 26
                        && cursor.getInt(26) == 1;
                apolloTrafficSignsEnabled = cursor.getColumnCount() > 27
                        && cursor.getInt(27) == 1;
                apolloStockUiEnabled = cursor.getColumnCount() > 28
                        && cursor.getInt(28) == 1;
                int rememberModesColumn = cursor.getColumnIndex("rememberModes");
                if (driveRememberColumn >= 0 && energyRememberColumn >= 0
                        && recycleRememberColumn >= 0) {
                    driveRememberLast = cursorBooleanDefaultTrue(cursor, driveRememberColumn);
                    energyRememberLast = cursorBooleanDefaultTrue(cursor, energyRememberColumn);
                    recycleRememberLast = cursorBooleanDefaultTrue(cursor, recycleRememberColumn);
                } else if (rememberModesColumn >= 0) {
                    // One release briefly exposed a global switch; migrate it to all three.
                    boolean global = cursorBooleanDefaultTrue(cursor, rememberModesColumn);
                    driveRememberLast = global;
                    energyRememberLast = global;
                    recycleRememberLast = global;
                } else {
                    // Old provider: keep its three independent enable values.
                    driveRememberLast = cursor.getColumnCount() > 6
                            ? legacyDriveEnabled : true;
                    energyRememberLast = cursor.getColumnCount() > 8
                            ? legacyEnergyEnabled : true;
                    recycleRememberLast = cursor.getColumnCount() > 7
                            ? legacyRecycleEnabled : true;
                }
                driveEnabled = driveRememberLast;
                energyEnabled = energyRememberLast;
                recycleEnabled = recycleRememberLast;
                rememberModes = driveRememberLast && energyRememberLast && recycleRememberLast;
                // col 12 — «Режим отладки»: эмуляция CAN в логи вместо реальной отправки
                boolean debugMode = cursor.getColumnCount() > 12 && cursor.getInt(12) == 1;
                // col 13 — «Сервисный режим дворников в холодную погоду»: старт/стоп WiperColdService
                boolean wiperColdMode = cursor.getColumnCount() > 13 && cursor.getInt(13) == 1;
                // cols 14,15 — команды кнопок на руле (короткое/долгое нажатие)
                if (cursor.getColumnCount() > 14) customCommandStarButton1 = cursor.getString(14);
                if (cursor.getColumnCount() > 15) customCommandStarButton2 = cursor.getString(15);
                // col 18 — «Пауза музыки при открытии двери водителя»: второй потребитель сигнала двери
                boolean pauseMediaOnDoor = cursor.getColumnCount() > 18 && cursor.getInt(18) == 1;
                // Named columns keep old b9 mode columns 29..31 from enabling media by accident.
                int pauseMediaCloseColumn = cursor.getColumnIndex("pauseMediaOnDoorClose");
                int pauseMediaAnyDoorColumn = cursor.getColumnIndex("pauseMediaOnAnyDoor");
                pauseMediaOnDoorClose = pauseMediaCloseColumn >= 0
                        && cursor.getInt(pauseMediaCloseColumn) == 1;
                pauseMediaOnAnyDoor = pauseMediaAnyDoorColumn >= 0
                        && cursor.getInt(pauseMediaAnyDoorColumn) == 1;
                int parkingHeadlightsColumn = cursor.getColumnIndex("headlightsOffInParking");
                headlightsOffInParking = parkingHeadlightsColumn >= 0
                        && cursor.getInt(parkingHeadlightsColumn) == 1;
                applyModeSideEffects(context, debugMode, wiperColdMode, pauseMediaOnDoor,
                        pauseMediaOnDoorClose, pauseMediaOnAnyDoor);
                saveModesCache(context, debugMode, wiperColdMode, pauseMediaOnDoor,
                        pauseMediaOnDoorClose, pauseMediaOnAnyDoor);
                ApplyEngine.noteLoadedModes(
                        driveMode, energy, recycle,
                        driveEnabled, energyEnabled, recycleEnabled,
                        driveRememberLast, energyRememberLast, recycleRememberLast);
                Log.i(MODES_LOG, "FRESH: driveEnabled=" + driveEnabled
                        + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                        + " remember=" + driveRememberLast + "/" + energyRememberLast
                        + "/" + recycleRememberLast
                        + " headlightsOffInParking=" + headlightsOffInParking
                        + " disablePedestrianSound=" + disablePedestrianSound
                        + " fragranceEnabled=" + fragranceEnabled
                        + " fragrance=" + fragranceTaste + "/" + fragranceDuration
                        + "/" + fragranceIntensity
                        + " apollo=" + apolloTlcEnabled + "/" + apolloTrafficLightsEnabled
                        + "/" + apolloGreenSoundEnabled + "/" + apolloTrafficSignsEnabled
                        + " stockUi=" + apolloStockUiEnabled
                        + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode
                        + " pauseMediaOnDoor=" + pauseMediaOnDoor
                        + " pauseMediaOnDoorClose=" + pauseMediaOnDoorClose
                        + " pauseMediaOnAnyDoor=" + pauseMediaOnAnyDoor);
                return 2;
            } else {
                Log.w(MODES_LOG, "Content provider not ready or missing columns"
                        + (cursor != null ? " cols=" + cursor.getColumnCount() : " cursor=null"));
            }
        } catch (Exception e) {
            Log.e(MODES_LOG, "Exception reading ContentProvider: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        // Провайдер не дал данных — пробуем локальный кэш (если разрешён)
        if (!allowCache) return 0;
        return loadModesFromCache(context) ? 1 : 0;
    }

    /** Совместимость: прежнее имя. */
    public static void initValueModes(Context context) {
        loadModes(context);
    }

    private static SharedPreferences nativePrefs(Context context) {
        return context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE);
    }

    /** Missing or NULL opt-out fields are enabled; only an explicit numeric zero disables them. */
    private static boolean cursorBooleanDefaultTrue(Cursor cursor, int column) {
        return cursor.getColumnCount() <= column || cursor.isNull(column) || cursor.getInt(column) != 0;
    }

    /** Сохраняет успешно прочитанный снимок настроек в NativePrefs (кэш на случай «глухого» пробуждения). */
    private static void saveModesCache(Context context, boolean debugMode, boolean wiperColdMode,
                                       boolean pauseMediaOnDoor, boolean pauseMediaOnDoorClose,
                                       boolean pauseMediaOnAnyDoor) {
        nativePrefs(context).edit()
                .putString("cacheDriveMode", driveMode)
                .putString("cacheEnergy", energy)
                .putString("cacheRecycle", recycle)
                .putString("cacheCustomCommand", customCommand)
                .putInt("cacheCustomCommandCount", customCommandCount)
                .putBoolean("cacheDriveEnabled", driveEnabled)
                .putBoolean("cacheRecycleEnabled", recycleEnabled)
                .putBoolean("cacheEnergyEnabled", energyEnabled)
                .putBoolean("cacheDriveRememberLast", driveRememberLast)
                .putBoolean("cacheEnergyRememberLast", energyRememberLast)
                .putBoolean("cacheRecycleRememberLast", recycleRememberLast)
                .putBoolean("cacheRememberModes", rememberModes)
                .putBoolean("headlightsOffInParking", headlightsOffInParking)
                .putBoolean("cacheHeadlightsOffInParking", headlightsOffInParking)
                .putBoolean("cacheDisablePedestrianSound", disablePedestrianSound)
                .putBoolean("cacheForcedEv", forcedEv)
                .putBoolean("cacheFragranceEnabled", fragranceEnabled)
                .putInt("cacheFragranceTaste", fragranceTaste)
                .putInt("cacheFragranceDuration", fragranceDuration)
                .putInt("cacheFragranceIntensity", fragranceIntensity)
                .putBoolean("cacheApolloTlcEnabled", apolloTlcEnabled)
                .putBoolean("cacheApolloTrafficLightsEnabled", apolloTrafficLightsEnabled)
                .putBoolean("cacheApolloGreenSoundEnabled", apolloGreenSoundEnabled)
                .putBoolean("cacheApolloTrafficSignsEnabled", apolloTrafficSignsEnabled)
                .putBoolean("cacheApolloStockUiEnabled", apolloStockUiEnabled)
                .putBoolean("cacheDebugMode", debugMode)
                .putBoolean("cacheWiperColdMode", wiperColdMode)
                .putBoolean("cachePauseMediaOnDoor", pauseMediaOnDoor)
                .putBoolean("cachePauseMediaOnDoorClose", pauseMediaOnDoorClose)
                .putBoolean("cachePauseMediaOnAnyDoor", pauseMediaOnAnyDoor)
                .putBoolean("cacheValid", true)
                .apply();
    }

    /** Восстанавливает настройки из кэша NativePrefs. @return true, если кэш существовал. */
    private static boolean loadModesFromCache(Context context) {
        SharedPreferences p = nativePrefs(context);
        if (!p.getBoolean("cacheValid", false)) {
            Log.w(MODES_LOG, "No cached modes available");
            return false;
        }
        driveMode          = p.getString("cacheDriveMode", driveMode);
        energy             = p.getString("cacheEnergy", energy);
        recycle            = p.getString("cacheRecycle", recycle);
        customCommand      = p.getString("cacheCustomCommand", customCommand);
        customCommandCount = p.getInt("cacheCustomCommandCount", customCommandCount);
        boolean legacyDriveEnabled = p.getBoolean("cacheDriveEnabled", false);
        boolean legacyRecycleEnabled = p.getBoolean("cacheRecycleEnabled", false);
        boolean legacyEnergyEnabled = p.getBoolean("cacheEnergyEnabled", false);
        boolean legacyRememberModes = p.getBoolean("cacheRememberModes", true);
        driveRememberLast = p.contains("cacheDriveRememberLast")
                ? p.getBoolean("cacheDriveRememberLast", true)
                : p.contains("cacheRememberModes") ? legacyRememberModes : legacyDriveEnabled;
        energyRememberLast = p.contains("cacheEnergyRememberLast")
                ? p.getBoolean("cacheEnergyRememberLast", true)
                : p.contains("cacheRememberModes") ? legacyRememberModes : legacyEnergyEnabled;
        recycleRememberLast = p.contains("cacheRecycleRememberLast")
                ? p.getBoolean("cacheRecycleRememberLast", true)
                : p.contains("cacheRememberModes") ? legacyRememberModes : legacyRecycleEnabled;
        driveEnabled = driveRememberLast;
        energyEnabled = energyRememberLast;
        recycleEnabled = recycleRememberLast;
        rememberModes = driveRememberLast && energyRememberLast && recycleRememberLast;
        headlightsOffInParking = p.contains("headlightsOffInParking")
                ? p.getBoolean("headlightsOffInParking", false)
                : p.getBoolean("cacheHeadlightsOffInParking", false);
        disablePedestrianSound = p.getBoolean("cacheDisablePedestrianSound", false);
        forcedEv = p.getBoolean("cacheForcedEv", false);
        fragranceEnabled = p.getBoolean("cacheFragranceEnabled", false);
        FragranceRestorePolicy.Settings fragrance = FragranceRestorePolicy.normalize(
                p.getInt("cacheFragranceTaste", FragranceRestorePolicy.DEFAULT_TASTE),
                p.getInt("cacheFragranceDuration", FragranceRestorePolicy.DEFAULT_DURATION),
                p.getInt("cacheFragranceIntensity", FragranceRestorePolicy.DEFAULT_INTENSITY));
        fragranceTaste = fragrance.taste;
        fragranceDuration = fragrance.duration;
        fragranceIntensity = fragrance.intensity;
        apolloTlcEnabled = p.getBoolean("cacheApolloTlcEnabled", false);
        apolloTrafficLightsEnabled = p.getBoolean("cacheApolloTrafficLightsEnabled", false);
        apolloGreenSoundEnabled = p.getBoolean("cacheApolloGreenSoundEnabled", false);
        apolloTrafficSignsEnabled = p.getBoolean("cacheApolloTrafficSignsEnabled", false);
        apolloStockUiEnabled = p.getBoolean("cacheApolloStockUiEnabled", false);
        boolean debugMode     = p.getBoolean("cacheDebugMode", false);
        boolean wiperColdMode = p.getBoolean("cacheWiperColdMode", false);
        boolean pauseMediaOnDoor = p.getBoolean("cachePauseMediaOnDoor", false);
        pauseMediaOnDoorClose = p.getBoolean("cachePauseMediaOnDoorClose", false);
        pauseMediaOnAnyDoor = p.getBoolean("cachePauseMediaOnAnyDoor", false);
        applyModeSideEffects(context, debugMode, wiperColdMode, pauseMediaOnDoor,
                pauseMediaOnDoorClose, pauseMediaOnAnyDoor);
        ApplyEngine.noteLoadedModes(
                driveMode, energy, recycle,
                driveEnabled, energyEnabled, recycleEnabled,
                driveRememberLast, energyRememberLast, recycleRememberLast);
        Log.i(MODES_LOG, "CACHE: driveEnabled=" + driveEnabled
                + " recycleEnabled=" + recycleEnabled + " energyEnabled=" + energyEnabled
                + " remember=" + driveRememberLast + "/" + energyRememberLast
                + "/" + recycleRememberLast
                + " headlightsOffInParking=" + headlightsOffInParking
                + " disablePedestrianSound=" + disablePedestrianSound
                + " fragranceEnabled=" + fragranceEnabled
                + " fragrance=" + fragranceTaste + "/" + fragranceDuration
                + "/" + fragranceIntensity
                + " apollo=" + apolloTlcEnabled + "/" + apolloTrafficLightsEnabled
                + "/" + apolloGreenSoundEnabled + "/" + apolloTrafficSignsEnabled
                + " stockUi=" + apolloStockUiEnabled
                + " debugMode=" + debugMode + " wiperColdMode=" + wiperColdMode
                + " pauseMediaOnDoor=" + pauseMediaOnDoor
                + " pauseMediaOnDoorClose=" + pauseMediaOnDoorClose
                + " pauseMediaOnAnyDoor=" + pauseMediaOnAnyDoor);
        return true;
    }

    /** Побочные эффекты настроек, не зависящие от отправки CAN: режим отладки и сервис-реактор двери водителя. */
    private static void applyModeSideEffects(Context context, boolean debugMode, boolean wiperColdMode,
                                              boolean pauseMediaOnDoor, boolean pauseMediaOnDoorClose,
                                              boolean pauseMediaOnAnyDoor) {
        CanSender.setDebugMode(debugMode);
        applyDoorReactor(context, wiperColdMode, pauseMediaOnDoor, pauseMediaOnDoorClose,
                pauseMediaOnAnyDoor);
    }

    // ------------------------------------------------------------------------
    // Прогрев высоковольтной батареи.
    //
    // Диагностический raw fallback для H97X. Production-путь BatteryHeatService использует
    // штатный OEM VehicleState API, чтобы CanBusService сам выбрал ABI конкретной платформы.
    private static final String[] BATTERY_HEAT_FRAMES = {
            "65 08 00 00 c1 c0 00 00 00 00",
    };

    /**
     * Ручной диагностический fallback. Автоматический и UI-пути его не вызывают. Шлёт
     * {@link #BATTERY_HEAT_FRAMES} напрямую; пустой массив — безопасный no-op с логом.
     */
    public static boolean sendBatteryHeatCommand() {
        if (BATTERY_HEAT_FRAMES.length == 0) {
            Log.w("$$$ MainActivity batteryHeat $$$",
                    "sendBatteryHeatCommand: CAN-команда прогрева ещё не задана (заглушка BATTERY_HEAT_FRAMES)");
            return false;
        }
        return setCanValues(1, CanFrameCodec.parseAll(BATTERY_HEAT_FRAMES), "battery preheat");
    }

    /** Немедленно применить звук пешеходов (тоггл с главного экрана). disabled=true → заглушить. */
    public static boolean sendPedestrianSoundCommand(boolean disabled) {
        return OemVehicleStateTransport.sendVehicleState(
                GlobalVars.SAVE_CONTEXT,
                VehicleRestorePolicy.PEDESTRIAN_SOUND,
                VehicleRestorePolicy.PEDESTRIAN_SOUND_ID,
                VehicleRestorePolicy.pedestrianSoundState(disabled),
                "pedestrian sound " + (disabled ? "off" : "on")).accepted();
    }

    /**
     * TX58 is accepted by the OEM Binder before the physical VSP CAN write is observable. The
     * stock implementation keeps firing this small independent command until TX57 reports the
     * requested state. Keep the retry bounded and run it only on the Native command worker so the
     * launcher/UI thread is never held while CanBusService wakes up.
     */
    public static boolean sendPedestrianSoundCommandWithRetry(boolean disabled) {
        final Context context = GlobalVars.SAVE_CONTEXT;
        if (context == null) {
            Log.w("$$$ PedestrianSound $$$", "no Native context");
            return false;
        }
        final OemVehicleStateTransport.StateKey key =
                new OemVehicleStateTransport.StateKey(
                        VehicleRestorePolicy.PEDESTRIAN_SOUND,
                        VehicleRestorePolicy.PEDESTRIAN_SOUND_ID);
        final int desired = VehicleRestorePolicy.pedestrianSoundState(disabled);
        boolean accepted = false;
        boolean sentAny = false;
        final int maxAttempts = 8;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Integer current = null;
            try {
                Map<OemVehicleStateTransport.StateKey, Integer> states =
                        OemVehicleStateTransport.readVehicleStates(
                                context, Collections.singleton(key));
                if (states != null) current = states.get(key);
            } catch (RuntimeException e) {
                Log.w("$$$ PedestrianSound $$$",
                        "TX57 read failed attempt=" + attempt + ": " + e.getMessage());
            }

            // Always submit at least one write. A stale cached read must not make a newly toggled
            // setting look successful without touching the OEM setter.
            if (sentAny && current != null && current == desired) {
                Log.i("$$$ PedestrianSound $$$", "verified state=" + current
                        + " desired=" + desired + " attempt=" + attempt);
                return true;
            }

            boolean sent = sendPedestrianSoundCommand(disabled);
            sentAny = true;
            accepted |= sent;
            Log.i("$$$ PedestrianSound $$$", "attempt=" + attempt
                    + " read=" + current + " desired=" + desired + " tx58=" + sent);
            if (attempt + 1 < maxAttempts) SystemClock.sleep(750L);
        }
        Log.w("$$$ PedestrianSound $$$", "bounded retry finished accepted=" + accepted
                + " desired=" + desired);
        return accepted;
    }

    /**
     * Старт/стоп {@link WiperColdService} — сервиса-реактора на открытие двери водителя. У него теперь
     * два независимых потребителя сигнала двери: «Сервисный режим дворников» ({@code wiperCold}) и
     * «Пауза музыки при открытии двери» ({@code pauseMediaOnDoor}). Оба флага дублируем в NativePrefs —
     * сам сервис читает их и гейтит соответствующее действие; {@link SetModesService} по {@code wiperCold}
     * решает про power-on reset дворников. Сервис живёт, пока включён хотя бы один потребитель.
     */
    public static void applyDoorReactor(Context context, boolean wiperEnabled, boolean pauseMediaOnDoor,
                                        boolean pauseMediaOnDoorClose, boolean pauseMediaOnAnyDoor) {
        if (context == null) return;
        Log.i("$$$ DoorReactor $$$", "applyDoorReactor: wiper=" + wiperEnabled
                + " pauseMedia=" + pauseMediaOnDoor
                + " resumeOnClose=" + pauseMediaOnDoorClose
                + " anyDoor=" + pauseMediaOnAnyDoor);
        context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("wiperCold", wiperEnabled)
                       .putBoolean("pauseMediaOnDoor", pauseMediaOnDoor)
                       .putBoolean("pauseMediaOnDoorClose", pauseMediaOnDoorClose)
                       .putBoolean("pauseMediaOnAnyDoor", pauseMediaOnAnyDoor).apply();
        Intent intent = new Intent(context, WiperColdService.class);
        if (wiperEnabled || pauseMediaOnDoor || pauseMediaOnAnyDoor) {
            context.startForegroundService(intent);
        } else {
            // НЕ сбрасываем wiperServiceActive: если дворники по нашей оценке в сервисном
            // режиме, их надо вернуть на ближайшем power on (даже с выключенной опцией) —
            // SetModesService.resetWiperColdOnPowerOn учитывает этот флаг.
            context.stopService(intent);
        }
    }

    /** Builds one validated pass before the first OEM request is submitted. */
    static CanRestorePlan createCanRestorePlan(boolean repeatOemOnNextPass) {
        Log.i("$$$ MainActivity runCmds $$$", "driveMode: " + driveMode + " energy: " + energy + " recycle: " + recycle
                + " | driveEnabled=" + driveEnabled + " energyEnabled=" + energyEnabled + " recycleEnabled=" + recycleEnabled
                + " disablePedestrianSound=" + disablePedestrianSound
                + " fragranceEnabled=" + fragranceEnabled
                + " apollo=" + apolloTlcEnabled + "/" + apolloTrafficLightsEnabled
                + "/" + apolloGreenSoundEnabled + "/" + apolloTrafficSignsEnabled);
        CanRestorePlan.Builder plan = new CanRestorePlan.Builder();
        final Context context = GlobalVars.SAVE_CONTEXT;
        final Map<String, Integer> primaryValues = new LinkedHashMap<>();
        final Map<String, Integer> trailingValues = new LinkedHashMap<>();
        final Map<String, Integer> stableIds = new LinkedHashMap<>();

        final boolean stockUiTarget = apolloStockUiEnabled;
        plan.addOnce("Apollo stock subscription/exam UI", () -> {
            ApolloSettingsRuntimeState.TargetApplyResult result =
                    ApolloSettingsRuntimeState.applyTarget(context, stockUiTarget);
            if (result == ApolloSettingsRuntimeState.TargetApplyResult.CONFIRMED) {
                return CanRestorePlan.OperationResult.CONFIRMED;
            }
            if (result == ApolloSettingsRuntimeState.TargetApplyResult.ACCEPTED_UNCONFIRMED) {
                return CanRestorePlan.OperationResult.ACCEPTED_UNCONFIRMED;
            }
            return CanRestorePlan.OperationResult.TRANSIENT_FAILURE;
        });

        if (driveEnabled) {
            if (!DriveModeCanTransport.appendStates(
                    context, driveMode, primaryValues, stableIds)) {
                throw new IllegalArgumentException("Unsupported drive mode: " + driveMode);
            }
        }
        VehicleRestorePolicy.appendPrimaryTo(
                primaryValues, energyEnabled, energy, forcedEv);
        VehicleRestorePolicy.appendRecuperationTo(
                trailingValues, recycleEnabled, recycle, driveMode);
        stableIds.putAll(VehicleRestorePolicy.stableIds());

        // Entitlements belong to the primary TX77 task; actual switches are submitted in the
        // following OEM task so ADCU capability bits are in place before PLC/GLA/TSR are changed.
        ApolloRestorePolicy.appendTo(primaryValues, trailingValues,
                apolloTlcEnabled, apolloTrafficLightsEnabled,
                apolloGreenSoundEnabled, apolloTrafficSignsEnabled);
        stableIds.putAll(ApolloRestorePolicy.stableIds());

        OemVehicleStateTransport.StateValue fragranceDurationState = null;
        if (fragranceEnabled) {
            FragranceRestorePolicy.Settings fragrance = FragranceRestorePolicy.normalize(
                    fragranceTaste, fragranceDuration, fragranceIntensity);
            primaryValues.putAll(FragranceRestorePolicy.fragranceBundle(fragrance));
            stableIds.putAll(FragranceRestorePolicy.stableIds());
            fragranceDurationState = new OemVehicleStateTransport.StateValue(
                    new OemVehicleStateTransport.StateKey(
                            FragranceRestorePolicy.DURATION_STATE,
                            FragranceRestorePolicy.DURATION_STATE_ID),
                    fragrance.duration);
        }

        if (!primaryValues.isEmpty() || !trailingValues.isEmpty()) {
            final OemVehicleStateTransport.StateValue firstState = fragranceDurationState;
            plan.addOperation("OEM vehicle restore snapshot", () ->
                    OemVehicleStateTransport.sendRestoreSequence(
                            context, firstState, primaryValues, trailingValues, stableIds,
                            "drive/energy/fragrance/Apollo entitlements then switches/recuperation")
                            .accepted()
                            ? CanRestorePlan.OperationResult.ACCEPTED_UNCONFIRMED
                            : CanRestorePlan.OperationResult.TRANSIENT_FAILURE,
                    repeatOemOnNextPass);
        }

        // Independent TX58: the OEM setter preserves the neighbouring VSP frame fields.
        final boolean pedestrianDisabled = disablePedestrianSound;
        plan.addOperation(
                "pedestrian sound mode " + (pedestrianDisabled ? "off" : "on"),
                () -> sendPedestrianSoundCommandWithRetry(pedestrianDisabled)
                        ? CanRestorePlan.OperationResult.ACCEPTED_UNCONFIRMED
                        : CanRestorePlan.OperationResult.TRANSIENT_FAILURE,
                repeatOemOnNextPass);
        return plan.build();
    }

    static CanRestorePlan createCanRestorePlan() {
        return createCanRestorePlan(false);
    }

    /** Compatibility one-shot; ApplyEngine keeps the plan across transient retries. */
    public static boolean runCmds() {
        try {
            return createCanRestorePlan().sendPending(
                    (frames, label) -> setCanValues(1, frames, label)).isComplete();
        } catch (IllegalArgumentException e) {
            Log.e("$$$ MainActivity runCmds $$$", "Permanent CAN plan error: " + e.getMessage());
            return false;
        }
    }
    public static void setDriveMode(String driveMode){
        sendDriveModeCommand(driveMode);
    }

    // Провайдер настроек RestoreMode — источник истины режимов (его читает loadModes/ApplyEngine и UI VoyahTune).
    private static final Uri MODES_PROVIDER_URI =
            Uri.parse("content://ru.big.town.restoremode.restoremodecontentprovider/");

    /**
     * Текущий СОХРАНЁННЫЙ режим (тот, что восстанавливается на пробуждении и показан в UI VoyahTune).
     * Читаем из провайдера RestoreMode; фолбэк — статик Native.
     * Нужно кнопке руля, чтобы циклировать ОТНОСИТЕЛЬНО реального режима (правильный первый клик).
     * @param isEnergy true → энергорежим, иначе режим вождения.
     */
    public static String currentSavedMode(Context context, boolean isEnergy) {
        return currentSavedMode(context, isEnergy ? "energy" : "driveMode");
    }

    /** Вариант для driveMode/energy/recycle; нужен назначаемой кнопке рекуперации. */
    public static String currentSavedMode(Context context, String modeKey) {
        int column = modeColumn(modeKey);
        if (column < 0) return null;
        // With remembering disabled, a steering action still needs the current in-session value
        // to cycle correctly, but that value must not be read back as persisted configuration.
        if (!isRememberModeEnabled(modeKey)) {
            return "energy".equals(modeKey) ? energy
                    : "recycle".equals(modeKey) ? recycle : driveMode;
        }
        Cursor c = null;
        try {
            c = context.getContentResolver().query(MODES_PROVIDER_URI, null, null, null, null);
            if (c != null && c.getCount() != 0 && c.getColumnCount() > column) {
                c.moveToFirst();
                String v = c.getString(column);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Exception e) {
            Log.w(MODES_LOG, "currentSavedMode: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return "energy".equals(modeKey) ? energy : "recycle".equals(modeKey) ? recycle : driveMode;
    }

    /** Быстрая проверка уже загруженного snapshot без повторного запроса к provider на каждый VState. */
    static boolean isLoadedMode(boolean isEnergy, String mode) {
        return isLoadedMode(isEnergy ? "energy" : "driveMode", mode);
    }

    /** Fast comparison against the full in-memory drive/energy/recuperation snapshot. */
    static boolean isLoadedMode(String modeKey, String mode) {
        if (mode == null) return false;
        if ("energy".equals(modeKey)) return mode.equals(energy);
        if ("recycle".equals(modeKey)) return mode.equals(recycle);
        return "driveMode".equals(modeKey) && mode.equals(driveMode);
    }

    /** Compatibility entry point for the former global switch; applies it to all three modes. */
    static void updateRememberModes(Context context, boolean enabled) {
        driveRememberLast = enabled;
        energyRememberLast = enabled;
        recycleRememberLast = enabled;
        driveEnabled = enabled;
        energyEnabled = enabled;
        recycleEnabled = enabled;
        rememberModes = enabled;
        ApplyEngine.noteRememberModes(enabled);
        if (context != null) {
            nativePrefs(context).edit()
                    .putBoolean("cacheDriveRememberLast", enabled)
                    .putBoolean("cacheEnergyRememberLast", enabled)
                    .putBoolean("cacheRecycleRememberLast", enabled)
                    .putBoolean("cacheDriveEnabled", enabled)
                    .putBoolean("cacheEnergyEnabled", enabled)
                    .putBoolean("cacheRecycleEnabled", enabled)
                    .putBoolean("cacheRememberModes", enabled)
                    .apply();
        }
        Log.i(MODES_LOG, "rememberModes=" + enabled);
    }

    /** Applies one of the three independent remember-last switches immediately. */
    static void updateRememberLastMode(Context context, String modeKey, boolean rememberLast) {
        if (!setRememberLastFlag(modeKey, rememberLast)) return;
        ApplyEngine.noteRememberLastMode(modeKey, rememberLast);
        rememberModes = driveRememberLast && energyRememberLast && recycleRememberLast;
        if (context != null) {
            nativePrefs(context).edit()
                    .putBoolean("cacheDriveRememberLast", driveRememberLast)
                    .putBoolean("cacheEnergyRememberLast", energyRememberLast)
                    .putBoolean("cacheRecycleRememberLast", recycleRememberLast)
                    .putBoolean("cacheDriveEnabled", driveEnabled)
                    .putBoolean("cacheEnergyEnabled", energyEnabled)
                    .putBoolean("cacheRecycleEnabled", recycleEnabled)
                    .putBoolean("cacheRememberModes", rememberModes)
                    .apply();
        }
        Log.i(MODES_LOG, "remember " + modeKey + "=" + rememberLast
                + " all=" + driveRememberLast + "/" + energyRememberLast + "/"
                + recycleRememberLast);
    }

    /**
     * Сохранить «последний активированный» режим как ИСТОЧНИК ИСТИНЫ: пишем в pref RestoreMode через
     * провайдер (переживёт пробуждение + попадёт в UI VoyahTune), плюс освежаем статик Native и его кэш
     * (fallback «глухого» пробуждения). Вызывает кнопка руля (SetModesReceiverDynamic.cycleMode); после
     * снятия value-ID на голове — синк внешней смены режима (см. ModeFeedbackController).
     * @param isEnergy true → энергорежим (pref "energy"), иначе режим вождения (pref "driveMode").
     */
    public static void persistSavedMode(Context context, boolean isEnergy, String mode) {
        persistSavedMode(context, isEnergy ? "energy" : "driveMode", mode);
    }

    /** Сохраняет driveMode/energy/recycle после явного действия пользователя. */
    public static void persistSavedMode(Context context, String modeKey, String mode) {
        if (context == null || mode == null || mode.isEmpty()) return;
        if (modeColumn(modeKey) < 0) return;
        boolean rememberLast = isRememberModeEnabled(modeKey);

        // Keep the live session coherent for steering cycles even when persistence is disabled.
        if ("energy".equals(modeKey)) energy = mode;
        else if ("recycle".equals(modeKey)) recycle = mode;
        else driveMode = mode;
        ApplyEngine.noteSavedMode(modeKey, mode);
        if (!rememberLast) {
            Log.i(MODES_LOG, "persistSavedMode session-only: " + modeKey
                    + " (remember switch is off)");
            return;
        }
        boolean written = false;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(modeKey, mode);
            // update() провайдера возвращает число записанных ключей (>0 = успех). Провайдер может быть на
            // миг недоступен (перезапуск/переустановка) → ловим исключение и НЕ считаем запись успешной.
            written = context.getContentResolver().update(MODES_PROVIDER_URI, cv, null, null) > 0;
        } catch (Exception e) {
            Log.w(MODES_LOG, "persistSavedMode provider: " + e.getMessage());
        }
        // Уведомить UI VoyahTune, чтобы селектор режима следил за текущим в реальном времени — даже когда
        // режим сменили штатным меню машины или кнопкой руля при ОТКРЫТОМ экране «Настройки автомобиля».
        try {
            Intent bi = new Intent("ru.big.town.anative.MODE_SYNCED");
            bi.setPackage("ru.big.town.restoremode");
            bi.putExtra("isEnergy", "energy".equals(modeKey));
            bi.putExtra("modeKey", modeKey);
            bi.putExtra("mode", mode);
            context.sendBroadcast(bi);
        } catch (Exception ignored) {}
        if (written) {
            // Провайдер (источник истины) записан → синхронно освежаем кэш, чтобы «глухое» пробуждение
            // (провайдер недоступен) восстановило именно этот режим и кэш НЕ расходился с провайдером.
            // cacheValid НЕ трогаем: его выставляет только ПОЛНЫЙ снимок saveModesCache; частичный — нельзя.
            try {
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                        .putString(modeCacheKey(modeKey), mode).apply();
            } catch (Exception ignored) {}
            Log.i(MODES_LOG, "persistSavedMode " + modeKey + "=" + mode + " (provider ok)");
        } else {
            // Не записали в источник истины → кэш НЕ трогаем (иначе разъедется с провайдером и на
            // пробуждении provider-first всё равно вернёт старое). Режим применён в CAN, но не переживёт сон.
            Log.w(MODES_LOG, "persistSavedMode " + modeKey + "=" + mode
                    + " — провайдер НЕ записан, режим не переживёт пробуждение");
        }
    }

    private static int modeColumn(String modeKey) {
        if ("driveMode".equals(modeKey)) return 0;
        if ("energy".equals(modeKey)) return 1;
        if ("recycle".equals(modeKey)) return 2;
        return -1;
    }

    private static String modeCacheKey(String modeKey) {
        if ("energy".equals(modeKey)) return "cacheEnergy";
        if ("recycle".equals(modeKey)) return "cacheRecycle";
        return "cacheDriveMode";
    }

    private static boolean isRememberModeEnabled(String modeKey) {
        if ("energy".equals(modeKey)) return energyRememberLast;
        if ("recycle".equals(modeKey)) return recycleRememberLast;
        return "driveMode".equals(modeKey) && driveRememberLast;
    }

    private static boolean setRememberLastFlag(String modeKey, boolean enabled) {
        if ("energy".equals(modeKey)) {
            energyRememberLast = enabled;
            energyEnabled = enabled;
            return true;
        }
        if ("recycle".equals(modeKey)) {
            recycleRememberLast = enabled;
            recycleEnabled = enabled;
            return true;
        }
        if ("driveMode".equals(modeKey)) {
            driveRememberLast = enabled;
            driveEnabled = enabled;
            return true;
        }
        return false;
    }

    /** Прочитать сохранённое состояние бинарного действия кнопки руля. */
    public static boolean currentSavedToggle(Context context, String key) {
        int column = "disablePedestrianSound".equals(key) ? 11 : "forcedEv".equals(key) ? 19 : -1;
        if (column < 0) return false;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(MODES_PROVIDER_URI, null, null, null, null);
            if (c != null && c.getCount() != 0 && c.getColumnCount() > column) {
                c.moveToFirst();
                return c.getInt(column) == 1;
            }
        } catch (Exception e) {
            Log.w(MODES_LOG, "currentSavedToggle " + key + ": " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return "forcedEv".equals(key) ? forcedEv : disablePedestrianSound;
    }

    /** Сохранить бинарное действие и синхронизировать открытый UI VoyahTune. */
    public static void persistSavedToggle(Context context, String key, boolean value) {
        if (context == null || (!"forcedEv".equals(key) && !"disablePedestrianSound".equals(key))) return;
        boolean written = false;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(key, value);
            written = context.getContentResolver().update(MODES_PROVIDER_URI, cv, null, null) > 0;
        } catch (Exception e) {
            Log.w(MODES_LOG, "persistSavedToggle provider " + key + ": " + e.getMessage());
        }
        if ("forcedEv".equals(key)) forcedEv = value; else disablePedestrianSound = value;
        try {
            Intent bi = new Intent("ru.big.town.anative.SETTING_SYNCED");
            bi.setPackage("ru.big.town.restoremode");
            bi.putExtra("key", key);
            bi.putExtra("value", value);
            context.sendBroadcast(bi);
        } catch (Exception ignored) {}
        if (written) {
            try {
                context.getSharedPreferences("NativePrefs", Context.MODE_PRIVATE).edit()
                        .putBoolean("forcedEv".equals(key) ? "cacheForcedEv" : "cacheDisablePedestrianSound", value)
                        .apply();
            } catch (Exception ignored) {}
            Log.i(MODES_LOG, "persistSavedToggle " + key + "=" + value + " (provider ok)");
        } else {
            Log.w(MODES_LOG, "persistSavedToggle " + key + "=" + value + " — провайдер НЕ записан");
        }
    }

}
