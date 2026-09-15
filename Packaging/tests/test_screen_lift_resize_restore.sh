#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HOST="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostActivity.java"
LANE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostTaskLane.java"
SNAPSHOT="$ROOT/Native/app/src/main/java/ru/big/town/anative/SplitHostTaskSnapshot.java"
RESTORER="$ROOT/Native/app/src/main/java/ru/big/town/anative/ScreenLiftTaskRestorer.java"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
MANIFEST="$ROOT/Native/app/src/main/AndroidManifest.xml"
VD="$ROOT/Packaging/inject/vd_bypass.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
INSTRUMENT="$ROOT/Packaging/inject/instrumentcard.js"
SPLIT_STORE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SplitStore.java"
SPLIT_SYNC="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SplitConfigSync.java"
QUICK_ACTIONS="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/QuickActionsController.java"
SPLIT_SAVE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/SplitRatioSaveReceiver.java"
ADVANCE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"

fail() {
    echo "screen-lift resize/restore contract test failed: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

node --check "$VD"
node --check "$DOCK"
node --check "$INSTRUMENT"

# The provisional compact viewport is one shared contract across the VD host, physical WM frames
# and the launcher dock. Keep 720 as the raised baseline until a head-unit measurement says otherwise.
require_fixed "$HOST" 'private static final int SCREEN_DOWN_HEIGHT_PX = 560;'
require_fixed "$HOST" 'private static final int SCREEN_UP_HEIGHT_PX = 720;'
require_fixed "$VD" 'compactBottom: 560'
require_fixed "$VD" 'voyahtune_win_compact_bottom", 560'
require_fixed "$DOCK" 'setDockViewHeight(views.up, compact ? 560 : 720, "screenUp");'
require_fixed "$DOCK" 'setDockViewHeight(views.group, compact ? -2 : -1, "radioGroup");'

# A new host reads the real lift state before any SurfaceView/VirtualDisplay is created. An existing
# host receives the completion event and lets the ordinary Surface lifecycle perform the actual VD resize.
read_line=$(grep -nF 'applyScreenLiftSize(readScreenLiftType());' "$HOST" | head -1 | cut -d: -f1)
surface_line=$(grep -nF 'setupSurface(left);' "$HOST" | head -1 | cut -d: -f1)
[ "$read_line" -lt "$surface_line" ] \
    || fail "new split reads the lift state after creating its Surface/VD"
require_fixed "$HOST" 'private static final String ACTION_SCREEN_LIFT_CHANGED = "action.qg.layout.changed";'
require_fixed "$HOST" 'private static final String ACTION_VD_RESIZED = "ru.big.town.anative.VD_RESIZED";'
require_fixed "$HOST" 'int actualType = readScreenLiftProperty(type);'
require_fixed "$HOST" 'if (actualType != type) {'
require_fixed "$HOST" 'applyScreenLiftSize(type);'
require_fixed "$HOST" 'pane.vd.resize(targetWidth, targetHeight, targetDpi);'
require_fixed "$HOST" 'schedulePaneResize(pane, width, height, dpi);'
require_fixed "$HOST" 'if (applyIncomingIntentInPlace(intent)) return;'
require_fixed "$HOST" 'private boolean applyIncomingIntentInPlace(Intent incoming)'
require_fixed "$HOST" 'pane.launched = false;'
require_fixed "$HOST" 'else if (sizeChanged) {'
require_fixed "$HOST" 'notifyPaneResized(pane);'
require_fixed "$HOST" 'intent.setPackage(pane.pkg);'
require_fixed "$HOST" 'sendBroadcast(intent, VD_RESIZE_PERMISSION);'
require_fixed "$HOST" 'unregisterReceiver(screenLiftReceiver);'
require_fixed "$HOST" 'public static final String EXTRA_RECONCILE = "reconcilePanes";'
require_fixed "$HOST" 'taskLane.requestPaneHealthCheck(this'
require_fixed "$HOST" 'if (paneHealthCheckPending) requestPaneHealthCheck();'
require_fixed "$LANE" 'boolean enabled = request.immediate || readWatchEnabled();'
require_fixed "$HOST" 'maskRight.setTranslationX(dx);'
require_fixed "$LANE" 'private static final int TASK_QUERY_LIMIT = 1000;'
require_fixed "$LANE" 'task.topActivity'
require_fixed "$LANE" 'task.baseActivity'
require_fixed "$SNAPSHOT" 'boolean belongsTo(String packageName)'

# Interactive resize is explicitly opt-out at two levels: a global safety switch and the per-preset
# flag. Turning the global switch off must prevent every launch path from sending a resizable split,
# while preserving the individual preset selection for a later re-enable.
require_fixed "$SPLIT_STORE" 'KEY_INTERACTIVE_DIVIDER_ENABLED = "splitInteractiveDividerEnabled"'
require_fixed "$SPLIT_STORE" 'preferences.getBoolean(KEY_INTERACTIVE_DIVIDER_ENABLED, true)'
require_fixed "$ADVANCE" 'splitInteractiveDividerSwitch = findViewById(R.id.splitInteractiveDividerSwitch);'
require_fixed "$ADVANCE" 'SplitStore.setInteractiveDividerEnabled(prefs, enabled);'
require_fixed "$QUICK_ACTIONS" 'preset.resizable'
require_fixed "$QUICK_ACTIONS" 'SplitStore.isInteractiveDividerEnabled(prefs)'
require_fixed "$SPLIT_SYNC" 'SplitStore.isInteractiveDividerEnabled(prefs)'
require_fixed "$SPLIT_SAVE" '!SplitStore.isInteractiveDividerEnabled(prefs)'

# Physical apps are resized through a normal WMS traversal; resolved/source-display bounds must not
# be copied during reparent.
require_fixed "$VD" 'requestFreeformTraversalOnce("screen lift type=" + FF.liftType);'
require_fixed "$VD" 'requestFreeformTraversalOnce("physical reparent pkg=" + pkg'
if grep -Eq 'task\.setBounds|task\.setAppBounds|\.mSizeCompatBounds' "$VD"; then
    fail "screen-lift/reparent path pins stale task bounds"
fi

# Preserve both physical foreground tasks across the OEM shell transition. Reuse the task id first
# (navigation stack intact), and never steal focus from a different third-party app opened meanwhile.
require_fixed "$RESTORER" 'filter.addAction(ACTION_START);'
require_fixed "$RESTORER" 'filter.addAction(ACTION_CHANGED);'
require_fixed "$RESTORER" 'if ((displayId != 0 && displayId != 1)'
require_fixed "$RESTORER" 'new SavedTask(task.taskId, displayId, top.getPackageName(), top)'
require_fixed "$RESTORER" 'activityManager.moveTaskToFront(saved.taskId, 0);'
require_fixed "$RESTORER" 'if (current != null && !LAUNCHER_PKG.equals(current.getPackageName())) {'
require_fixed "$RESTORER" 'private static final long RESTORE_DELAY_MS = 2_000L;'
require_fixed "$RESTORER" '"getDpyTopAppInfo", Context.class, int.class, int.class);'
require_fixed "$RESTORER" 'getTop.invoke(null, context, displayId, 4);'
require_fixed "$RESTORER" 'options.setLaunchDisplayId(saved.displayId);'
require_fixed "$RESTORER" 'int actualType = readLiftProperty(type);'
require_fixed "$RESTORER" 'if (actualType != type) {'
require_fixed "$SERVICE" 'screenLiftTaskRestorer = new ScreenLiftTaskRestorer(getApplicationContext());'
require_fixed "$SERVICE" 'if (BuildConfig.IS_FULL) {'
require_fixed "$SERVICE" 'if (liftRestorer != null) liftRestorer.close();'
require_fixed "$MANIFEST" '<uses-permission android:name="android.permission.REORDER_TASKS" />'

# Compact mode is overridden only for the driver: Home and user slots 1/2 remain addressable.
# Passenger compact layout stays OEM-controlled (one-button Home dock).
require_fixed "$DOCK" 'setDockViewVisibility(views.down, 8, "screenDown");'
require_fixed "$DOCK" 'setDockViewVisibility(views.home, 0, "home");'
require_fixed "$DOCK" 'setDockViewVisibility(views.slot1, compact && !compactSlot1 ? 8 : 0, "slot1");'
require_fixed "$DOCK" 'setDockViewVisibility(views.slot2, compact && !compactSlot2 ? 8 : 0, "slot2");'
require_fixed "$DOCK" 'var compactSlot1 = dockPackage(0, 1, false) !== "none"'
require_fixed "$DOCK" 'var compactSlot2 = dockPackage(0, 2, false) !== "none"'
require_fixed "$DOCK" 'var controllerShow = LiftController.show.overload();'
require_fixed "$DOCK" 'controller show reconciled driver layout'
require_fixed "$DOCK" 'setTimeout(updateAllNavbars, 8000);'
if grep -Eq 'setTimeout\(updateAllNavbars, (12000|15000)\);' "$DOCK"; then
    fail "launcher startup reconciliation keeps unnecessary late polling passes"
fi
require_fixed "$DOCK" 'setDockViewVisibility(views.allApps, compact ? 8 : 0, "allApps");'
require_fixed "$DOCK" 'setDockViewVisibility(views.extra1, compact ? 8 : 0, "slot3");'
require_fixed "$DOCK" 'setDockViewVisibility(views.extra2, compact ? 8 : 0, "slot4");'
require_fixed "$DOCK" 'if (sid !== 0) return; // passenger compact remains completely OEM-controlled (Home only)'
require_fixed "$DOCK" 'var driverTemperature = dockField(instance, "mScreenUpTemperatureContentView");'
require_fixed "$DOCK" 'setDockViewVisibility(driverTemperature, compact ? 8 : 0, "driverTemperature");'
require_fixed "$DOCK" 'var controllerLift = LiftController.doScreenLift.overload('
require_fixed "$DOCK" 'var navigationBar = runtimeObject(dockField(this, "mNavigationBar"));'
require_fixed "$DOCK" "var launcherScreenLift = LM2.doScreenLift.overload('int');"
require_fixed "$DOCK" 'setTimeout(updateAllNavbars, 50);'
require_fixed "$DOCK" 'setTimeout(updateAllNavbars, 250);'
require_fixed "$DOCK" 'function applyNativeMediaIdentity(view)'
require_fixed "$DOCK" 'fieldValue(view, "mediaName")'
require_fixed "$INSTRUMENT" 'function applyNativeMediaIdentity(view)'
require_fixed "$INSTRUMENT" 'native media resource identity hook installed'
require_fixed "$DOCK" 'return mainOnClick.call(this, view);'
require_fixed "$DOCK" 'if (screenId !== 0) return "none";'
if grep -Eq 'mScreenUp(AirView|SeatView)' "$DOCK"; then
    fail "driver compact override writes passenger Air/Seat views"
fi

echo "screen-lift resize/restore contract test: OK"
