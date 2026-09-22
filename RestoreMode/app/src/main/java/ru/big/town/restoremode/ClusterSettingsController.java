package ru.big.town.restoremode;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;

/** UI owner for the Full-only cluster section. */
final class ClusterSettingsController {
    private final AdvanceActivity activity;
    private final SharedPreferences prefs;
    private final LinearLayout list;

    ClusterSettingsController(AdvanceActivity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
        FeatureSettingsMigration.migrate(prefs);
        list = activity.findViewById(R.id.clusterAppList);
        Switch gesture = activity.findViewById(R.id.switchClusterGesture);
        gesture.setChecked(prefs.getBoolean("clusterGestureEnabled", false));
        gesture.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("clusterGestureEnabled", checked).apply();
            SplitConfigSync.pushClusterWidgets(activity, prefs);
        });
        activity.findViewById(R.id.buttonAddClusterApp).setOnClickListener(v ->
                activity.showAppPicker("Разрешить на приборке", (pkg, label) -> {
                    List<String> packages = ClusterAppStore.load(prefs);
                    if (!packages.contains(pkg)) packages.add(pkg);
                    ClusterAppStore.save(prefs, packages);
                    render();
                    SplitConfigSync.pushClusterWidgets(activity, prefs);
                }));
        render();
    }

    private void render() {
        list.removeAllViews();
        List<String> packages = ClusterAppStore.load(prefs);
        if (packages.isEmpty()) {
            TextView empty = label("Нет разрешённых приложений");
            empty.setTextColor(0xff888888);
            list.addView(empty);
            return;
        }
        for (String pkg : packages) {
            LinearLayout row = row();
            TextView text = label(activity.applicationLabel(pkg) + "  ·  " + pkg);
            row.addView(text, new LinearLayout.LayoutParams(0, 64, 1f));
            Button remove = button("Удалить");
            remove.setOnClickListener(v -> {
                List<String> current = ClusterAppStore.load(prefs);
                current.remove(pkg);
                ClusterAppStore.save(prefs, current);
                render();
                SplitConfigSync.pushClusterWidgets(activity, prefs);
            });
            row.addView(remove);
            list.addView(row);
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(10, 8, 10, 8);
        return row;
    }

    private TextView label(String value) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextColor(Color.WHITE); text.setTextSize(19f);
        text.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return text;
    }

    private Button button(String value) {
        Button button = new Button(activity); button.setText(value);
        return button;
    }
}
