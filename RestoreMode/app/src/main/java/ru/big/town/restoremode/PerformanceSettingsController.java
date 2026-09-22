package ru.big.town.restoremode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

/** UI owner for the staged reversible performance profile. */
final class PerformanceSettingsController {
    private static final String RESULT = "ru.big.town.anative.PERFORMANCE_PROFILE_RESULT";
    private final AdvanceActivity activity;
    private final TextView status;
    private final Button apply;
    private final Button restore;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            setBusy(false);
            status.setText(intent.getStringExtra("status"));
        }
    };

    PerformanceSettingsController(AdvanceActivity activity, SharedPreferences prefs) {
        this.activity = activity;
        status = activity.findViewById(R.id.textPerformanceStatus);
        apply = activity.findViewById(R.id.buttonApplyBalancedProfile);
        restore = activity.findViewById(R.id.buttonRestorePerformanceProfile);
        apply.setOnClickListener(v -> send("ru.big.town.anative.PERFORMANCE_PROFILE_APPLY"));
        restore.setOnClickListener(v -> send("ru.big.town.anative.PERFORMANCE_PROFILE_RESTORE"));
        ContextCompat.registerReceiver(activity, receiver, new IntentFilter(RESULT),
                ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    private void send(String action) {
        setBusy(true);
        status.setText("Выполняется поэтапно…");
        Intent intent = new Intent(action).setClassName("ru.big.town.anative",
                "ru.big.town.anative.SetModesConfigReceiver");
        activity.sendBroadcast(intent, "ru.big.town.anative.permission.BIND_SET_MODES_SERVICE");
    }

    private void setBusy(boolean busy) {
        apply.setEnabled(!busy); restore.setEnabled(!busy);
        apply.setAlpha(busy ? .45f : 1f); restore.setAlpha(busy ? .45f : 1f);
    }

    void close() {
        if (!registered) return;
        registered = false;
        try { activity.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
    }
}
