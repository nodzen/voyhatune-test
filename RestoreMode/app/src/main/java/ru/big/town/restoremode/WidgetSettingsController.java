package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** UI owner for enabling and sorting native media-area cards. */
final class WidgetSettingsController {
    private final AdvanceActivity activity;
    private final SharedPreferences prefs;
    private final LinearLayout list;

    WidgetSettingsController(AdvanceActivity activity, SharedPreferences prefs) {
        this.activity = activity; this.prefs = prefs;
        FeatureSettingsMigration.migrate(prefs);
        list = activity.findViewById(R.id.customWidgetList);
        activity.findViewById(R.id.buttonAddMusicCard).setOnClickListener(v -> addSingleton("music"));
        activity.findViewById(R.id.buttonAddTripCard).setOnClickListener(v -> addSingleton("trip"));
        activity.findViewById(R.id.buttonAddCarCard).setOnClickListener(v -> addSingleton("car"));
        activity.findViewById(R.id.buttonAddAppCard).setOnClickListener(v ->
                activity.showAppPicker("Приложение в карточке", (pkg, label) -> {
                    List<CustomWidgetStore.Card> cards = CustomWidgetStore.load(prefs);
                    cards.add(new CustomWidgetStore.Card("app", pkg, AppDpiStore.get(prefs, pkg)));
                    save(cards);
                }));
        render();
    }

    private void addSingleton(String kind) {
        List<CustomWidgetStore.Card> cards = CustomWidgetStore.load(prefs);
        for (CustomWidgetStore.Card card : cards) if (kind.equals(card.kind)) return;
        cards.add(new CustomWidgetStore.Card(kind));
        save(cards);
    }

    private void save(List<CustomWidgetStore.Card> cards) {
        CustomWidgetStore.save(prefs, cards);
        render();
        SplitConfigSync.pushClusterWidgets(activity, prefs);
    }

    private void render() {
        list.removeAllViews();
        List<CustomWidgetStore.Card> cards = CustomWidgetStore.load(prefs);
        for (int i = 0; i < cards.size(); i++) {
            final int index = i;
            CustomWidgetStore.Card card = cards.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(8, 8, 8, 8);
            TextView label = new TextView(activity);
            label.setText((i + 1) + ". " + label(card));
            label.setTextColor(Color.WHITE); label.setTextSize(20f);
            row.addView(label, new LinearLayout.LayoutParams(0, 64, 1f));
            Button up = button("↑"); up.setEnabled(i > 0);
            up.setOnClickListener(v -> move(index, -1)); row.addView(up);
            Button down = button("↓"); down.setEnabled(i + 1 < cards.size());
            down.setOnClickListener(v -> move(index, 1)); row.addView(down);
            Button remove = button("×");
            remove.setOnClickListener(v -> {
                List<CustomWidgetStore.Card> current = CustomWidgetStore.load(prefs);
                if (index < current.size()) current.remove(index);
                save(current);
            });
            row.addView(remove);
            list.addView(row);
        }
    }

    private void move(int index, int delta) {
        List<CustomWidgetStore.Card> cards = CustomWidgetStore.load(prefs);
        int target = index + delta;
        if (index < 0 || target < 0 || index >= cards.size() || target >= cards.size()) return;
        CustomWidgetStore.Card card = cards.remove(index);
        cards.add(target, card);
        save(cards);
    }

    private String label(CustomWidgetStore.Card card) {
        if ("music".equals(card.kind)) return "Музыка (OEM)";
        if ("trip".equals(card.kind)) return "Поездка";
        if ("car".equals(card.kind)) return "Автомобиль";
        return activity.applicationLabel(card.packageName) + "  ·  DPI "
                + (card.dpi == 0 ? "авто" : card.dpi);
    }

    private Button button(String text) {
        Button button = new Button(activity); button.setText(text);
        button.setMinWidth(58); return button;
    }
}
