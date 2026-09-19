#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
CONTROLLER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeFeedbackController.java"
DECODER="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeFeedbackDecoder.java"
VEHICLE_STATE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/VehicleStateControllers.java"
SERVICE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesService.java"
LIGHT="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/LightSensorService.java"
TRIPS="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/TripStatsService.java"
MODE_POLICY="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ModeSyncPolicy.java"
APPLY_ENGINE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/ApplyEngine.java"
NATIVE_FACADE="$REPO_ROOT/Native/app/src/main/java/ru/big/town/anative/VehicleCommandFacade.java"
ADVANCE="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
PROVIDER="$REPO_ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
ADVANCE_LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
QUICK_LAYOUT="$REPO_ROOT/RestoreMode/app/src/main/res/layout/page_quick_actions.xml"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

require_fixed() {
    grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"
}

# The process-wide composition root owns the single shared vehicle-state subscription. The mode
# controller only decodes and applies mode feedback; it has no dependency on trip statistics.
require_fixed "$SERVICE" 'VehicleStateControllers.get(getApplicationContext())'
require_fixed "$VEHICLE_STATE" 'CanBusEventRouter.INTEREST_CONNECTION'
require_fixed "$VEHICLE_STATE" 'CanBusEventRouter.INTEREST_VEHICLE_STATE'
require_fixed "$VEHICLE_STATE" 'ModeFeedbackController.create(appContext, stateHandler)'
require_fixed "$DECODER" 'RECYCLE_MODE_VSTATE_ID = VehicleRestorePolicy.REGEN_LEVEL_ID'
require_fixed "$CONTROLLER" 'ModeFeedbackDecoder.decode(id, state)'
require_fixed "$CONTROLLER" 'ApplyEngine.persistModeFeedbackIfAllowed('
require_fixed "$CONTROLLER" 'new IntentFilter(ACTION_REMEMBER_LAST_CHANGED), BIND_PERMISSION'

if grep -Eq 'ModeFeedback|MODE_REMEMBER|persistModeFeedback|INTEREST_VEHICLE_STATE' "$TRIPS"; then
    fail "TripStatsService contains vehicle-mode responsibilities"
fi

# OEM defaults during wake must not replace the stored target. After settle, only opted-in mode
# feedback may become the source of truth.
require_fixed "$MODE_POLICY" 'POST_RESTORE_SETTLE_MS = 30_000L'
require_fixed "$MODE_POLICY" \
    'return acceptsExternalFeedback(modeKey) ? Decision.ACCEPT : Decision.IGNORE;'
require_fixed "$MODE_POLICY" 'acceptsExternalFeedback(modeKey)'
require_fixed "$APPLY_ENGINE" 'MODE_SYNC_POLICY.canPersist('

# Remembering is three independent opt-in switches. Each one controls both restore and learning
# of the latest actual vehicle value; no manual mode selector remains in the settings.
for label in \
    'android:id="@+id/switchRememberDriveMode"' \
    'android:id="@+id/switchRememberEnergyMode"' \
    'android:id="@+id/switchRememberRecycleMode"'; do
    total=0
    for layout in "$ADVANCE_LAYOUT" "$QUICK_LAYOUT"; do
        count=$(grep -F -c -- "$label" "$layout" || true)
        total=$((total + count))
    done
    [ "$total" -eq 1 ] || fail "remember switch must be shown exactly once: $label"
done
for old_id in switchDriveMode switchEnergy switchRecycle switchRememberModes checkBox34 \
    drive_modes_group energy_modes_group recycle_modes_group; do
    if grep -Fq -- "@+id/$old_id" "$ADVANCE_LAYOUT"; then
        fail "obsolete manual mode control remains: $old_id"
    fi
done
require_fixed "$PROVIDER" 'sharedPreferences.contains("rememberModes")'
require_fixed "$PROVIDER" '"driveRememberLast",        // 31'
require_fixed "$PROVIDER" '"headlightsOffInParking",  // 35'
require_fixed "$ADVANCE" 'putExtra(EXTRA_MODE_KEY, modeKey)'
require_fixed "$ADVANCE" 'prefs.contains("rememberModes")'
require_fixed "$NATIVE_FACADE" 'p.getBoolean("cacheRememberModes", true)'
require_fixed "$NATIVE_FACADE" 'cursor.getColumnIndex("driveRememberLast")'
require_fixed "$NATIVE_FACADE" 'cursor.getColumnIndex("headlightsOffInParking")'
require_fixed "$NATIVE_FACADE" 'isRememberModeEnabled(modeKey)'
require_fixed "$LIGHT" 'ACTION_PARKING_HEADLIGHTS_CHANGED'
require_fixed "$LIGHT" 'gear=P; parking headlight switch'
require_fixed "$SERVICE" 'stopLightSensorServiceIfUnused()'
require_fixed "$CONTROLLER" 'VehicleCommandFacade.updateRememberLastMode(appContext, modeKey, rememberLast)'
require_fixed "$CONTROLLER" 'VehicleCommandFacade.updateRememberModes(appContext, rememberModes)'
require_fixed "$NATIVE_FACADE" 'cursor.getColumnCount() <= column || cursor.isNull(column)'
require_fixed "$ADVANCE" 'ru.big.town.anative.MODE_REMEMBER_CHANGED'

echo "PASS: vehicle-mode feedback is isolated, wake-safe, independently remembered, and parking headlights are wired"
