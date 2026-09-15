package ru.big.town.restoremode;

/** Stable cross-process actions used by the settings quick-actions section and trip history. */
final class QuickActionsContract {
    static final String NATIVE_PACKAGE = "ru.big.town.anative";
    static final String NATIVE_PERMISSION =
            "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE";
    static final String TRIP_UPDATE = "ru.big.town.anative.TRIP_UPDATE";
    static final String TRIP_REQUEST = "ru.big.town.anative.REQUEST_TRIP_UPDATE";
    static final String TRIP_RESET = "ru.big.town.anative.TRIP_RESET";
    static final String BATTERY_UPDATE = "ru.big.town.anative.BATTERY_HEAT_UPDATE";
    static final String BATTERY_REQUEST = "ru.big.town.anative.REQUEST_BATTERY_HEAT";
    static final String BATTERY_ACTIVATE = "ru.big.town.anative.BATTERY_HEAT_ACTIVATE";
    static final String POWER_HOLD_REQUEST = "ru.big.town.anative.REQUEST_POWER_HOLD_STATUS";
    static final String POWER_HOLD_UPDATE = "ru.big.town.anative.POWER_HOLD_STATUS_UPDATE";

    static final int MSG_POWER_HOLD = 20;
    static final int MSG_WASH_MODE = 23;
    static final int MSG_OPEN_APP_OR_SPLIT = 34;

    private QuickActionsContract() {}
}
