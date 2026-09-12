#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
RECEIVER="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesConfigReceiver.java"
UI="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"

fail() { echo "door media switch sync test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
line_first() { grep -nF -- "$2" "$1" | head -n1 | cut -d: -f1; }

action_line=$(line_first "$RECEIVER" 'ACTION_DOOR_MEDIA_PAUSE_CHANGED.equals(action)')
flavor_guard=$(line_first "$RECEIVER" 'if (!BuildConfig.IS_FULL) return;')
[ "$action_line" -lt "$flavor_guard" ] || fail "door-media action is unavailable in light flavor"
require "$RECEIVER" 'applyDoorMediaSetting(context, "pauseMediaOnDoor",'
require "$RECEIVER" 'prefs.edit().putBoolean(key, enabled).apply();'
require "$RECEIVER" 'context.stopService(service);'
require "$UI" 'setClassName(NATIVE_PACKAGE, "ru.big.town.anative.SetModesConfigReceiver")'
require "$UI" 'sendBroadcast(changed, NATIVE_CONFIG_PERMISSION);'

echo "PASS: door media switch is synchronized immediately across RestoreMode and Native"
