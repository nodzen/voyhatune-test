package ru.big.town.restoremode;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Catalog and persistence for the four OEM home-screen widget shelves.
 *
 * <p>An empty value means "use the factory order". Once a shelf is edited, its CSV contains only
 * known OEM operations in the order selected by the user. The launcher hook applies that order to
 * the actual JSON returned by VehicleHiBoardDataManager, so an operation unavailable on a given
 * firmware is simply omitted.</p>
 */
final class HomeWidgetStore {
    static final String[] REGIONS = {"left_small", "left_big", "right_small", "right_big"};
    static final String[] REGION_LABELS = {
            "Левая маленькая панель", "Левая большая панель",
            "Правая маленькая панель", "Правая большая панель"
    };
    private static final String PREF_PREFIX = "homeWidgets_";

    private static final String[][] OPERATIONS = {
            {"position1", "heated_seat", "power_mode", "position2", "ventilated_seat",
                    "open_fuel_flap", "open_charge_port", "position3", "massage_seat",
                    "easy_enter", "parking"},
            {"cat_nap_mode", "sun_roof"},
            {"open_windows", "close_windows", "ventilation", "unlock", "easy_exit", "hdc",
                    "ped_alarm", "power_hold_mode", "rear_screen", "swc_heat", "trunk"},
            {"drive_mode", "atm", "fragrance"}
    };
    private static final String[][] LABELS = {
            {"Позиция 1", "Подогрев сиденья", "Power Mode", "Позиция 2", "Вентиляция сиденья",
                    "Лючок топлива", "Зарядный порт", "Позиция 3", "Массаж сиденья",
                    "Удобная посадка", "Парковка"},
            {"Режим отдыха", "Люк"},
            {"Открыть окна", "Закрыть окна", "Вентиляция", "Замки", "Удобный выход", "HDC",
                    "Звук пешеходов", "Power Hold", "Задний экран", "Подогрев руля", "Багажник"},
            {"Режим езды", "ATM", "Ароматизатор"}
    };

    private HomeWidgetStore() {}

    static String prefKey(String region) { return PREF_PREFIX + region; }

    static String getCsv(SharedPreferences prefs, String region) {
        return prefs.getString(prefKey(region), "");
    }

    static void setCsv(SharedPreferences prefs, String region, List<String> selected) {
        if (selected == null || selected.isEmpty()) {
            prefs.edit().putString(prefKey(region), "none").apply();
            return;
        }
        prefs.edit().putString(prefKey(region), encode(selected)).apply();
    }

    static String encode(List<String> selected) {
        StringBuilder out = new StringBuilder();
        for (String operation : selected) {
            if (!isKnown(operation)) continue;
            if (out.length() > 0) out.append(',');
            out.append(operation);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    static List<String> decode(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty() || "none".equals(csv.trim())) return result;
        for (String raw : csv.split(",")) {
            String operation = raw.trim();
            if (isKnown(operation) && !result.contains(operation)) result.add(operation);
        }
        return result;
    }

    static String[] operations(int region) { return OPERATIONS[region]; }
    static String[] labels(int region) { return LABELS[region]; }

    static boolean[] checked(int region, String csv) {
        List<String> selected = decode(csv);
        boolean[] result = new boolean[OPERATIONS[region].length];
        for (int i = 0; i < result.length; i++) result[i] = selected.contains(OPERATIONS[region][i]);
        return result;
    }

    static List<String> selectedInCatalogOrder(int region, boolean[] checked) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < OPERATIONS[region].length; i++) {
            if (checked != null && i < checked.length && checked[i]) result.add(OPERATIONS[region][i]);
        }
        return result;
    }

    static String summary(SharedPreferences prefs, int region) {
        String csv = getCsv(prefs, REGIONS[region]);
        if (csv == null || csv.trim().isEmpty()) return "штатный набор";
        List<String> selected = decode(csv);
        return "none".equals(csv.trim()) ? "0 выбрано" : selected.size() + " выбрано";
    }

    private static boolean isKnown(String operation) {
        if (operation == null || operation.isEmpty()) return false;
        for (String[] region : OPERATIONS) if (Arrays.asList(region).contains(operation)) return true;
        return false;
    }
}
