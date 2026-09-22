#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
RECEIVER="$ROOT/Native/app/src/main/java/ru/big/town/anative/SetModesConfigReceiver.java"
UI="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"

fail() { echo "door media switch sync test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
require "$RECEIVER" 'applyDoorMediaSetting(context, "pauseMediaOnDoor",'
require "$RECEIVER" 'prefs.edit().putBoolean(key, enabled).apply();'
require "$RECEIVER" 'context.stopService(service);'
require "$UI" 'setClassName(NATIVE_PACKAGE, "ru.big.town.anative.SetModesConfigReceiver")'
require "$UI" 'sendBroadcast(changed, NATIVE_CONFIG_PERMISSION);'

echo "PASS: door media switch is synchronized immediately across RestoreMode and Native"
