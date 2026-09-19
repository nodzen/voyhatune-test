#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
MAIN="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/MainActivity.java"
ADVANCE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
QUICK="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/QuickActionsController.java"
CLIENT="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/NativeServiceClient.java"
RESTORE_MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"
ADVANCE_LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
NATIVE_MANIFEST="$ROOT/Native/app/src/main/AndroidManifest.xml"
FACADE="$ROOT/Native/app/src/main/java/ru/big/town/anative/VehicleCommandFacade.java"

fail() { echo "settings-only UI contract test failed: $*" >&2; exit 1; }
require_fixed() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }
forbid_fixed() { grep -Fq -- "$2" "$1" && fail "$1 still contains: $2" || true; }

require_fixed "$MAIN" 'public class MainActivity extends AdvanceActivity'
require_fixed "$RESTORE_MANIFEST" 'android:name=".MainActivity"'
require_fixed "$ADVANCE_LAYOUT" '<include layout="@layout/page_quick_actions" />'
require_fixed "$ADVANCE_LAYOUT" 'android:id="@+id/switchRememberDriveMode"'
require_fixed "$ADVANCE_LAYOUT" 'android:id="@+id/switchRememberEnergyMode"'
require_fixed "$ADVANCE_LAYOUT" 'android:id="@+id/switchRememberRecycleMode"'
forbid_fixed "$ROOT/RestoreMode/app/src/main/res/layout/page_quick_actions.xml" 'switchRememberDriveMode'
forbid_fixed "$ROOT/RestoreMode/app/src/main/res/layout/page_quick_actions.xml" 'switchRememberEnergyMode'
forbid_fixed "$ROOT/RestoreMode/app/src/main/res/layout/page_quick_actions.xml" 'switchRememberRecycleMode'
require_fixed "$ADVANCE_LAYOUT" 'android:id="@+id/switchPedestrianSound"'
require_fixed "$ADVANCE_LAYOUT" 'android:id="@+id/switchForcedEv"'
require_fixed "$ADVANCE_LAYOUT" 'android:showText="false"'
require_fixed "$ROOT/RestoreMode/app/src/main/res/values/styles.xml" 'android:minHeight">52dp'
require_fixed "$ADVANCE" 'prefs.getString("customCommand", "")'
require_fixed "$ADVANCE" 'if (activityResumed && index == 0) quickActions.start();'
require_fixed "$QUICK" 'QuickActionsContract.MSG_POWER_HOLD'
require_fixed "$QUICK" 'QuickActionsContract.MSG_WASH_MODE'
require_fixed "$QUICK" 'QuickActionsContract.MSG_OPEN_APP_OR_SPLIT'
require_fixed "$CLIENT" 'bindService(intent, connection, Context.BIND_AUTO_CREATE)'
forbid_fixed "$ADVANCE" 'GlobalVars.'
forbid_fixed "$NATIVE_MANIFEST" 'android:name=".MainActivity"'
forbid_fixed "$FACADE" 'extends AppCompatActivity'

echo "PASS: launcher is a settings shell and vehicle commands are lifecycle-owned"
