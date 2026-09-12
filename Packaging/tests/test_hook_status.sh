#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
LOADER="$ROOT/Packaging/system/load.bin"
PROVIDER="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
CONTRACT="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/HookStatusContract.java"
APP_MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
FULL_INSTALL="$ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$ROOT/Packaging/installer/full/install.bat"
LIGHT_INSTALL="$ROOT/Packaging/installer/light/install.sh"
LIGHT_INSTALL_BAT="$ROOT/Packaging/installer/light/install.bat"

fail() { echo "hook status/install test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}
forbid_ci() {
    if grep -Fiq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}

forbid "$LOADER" 'HOOK_MANIFEST'
forbid "$LOADER" 'sha256_path'
forbid "$LOADER" 'INTEGRITY'
forbid "$LOADER" 'manifest='
require "$FULL_INSTALL" 'rm -f /data/local/bin/voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL" 'install_required_data_file voyahtune-hook-manifest.json'
forbid "$FULL_INSTALL" 'host_sha256'
forbid "$FULL_INSTALL" 'verify_hook_manifest'

require "$LOADER" 'mv -f "$STATUS_STAGE" "$HOOK_STATUS_FILE"'
require "$LOADER" 'if [ "$HOOK_STATUS_LOCAL_PAYLOAD" != "$HOOK_STATUS_PAYLOAD" ]; then'
require "$LOADER" '/system/bin/content call --user 0'
require "$LOADER" "*'stored=true'*)"
require "$LOADER" 'HOOK_STATUS_PROVIDER_MAX_ATTEMPTS=3'
require "$LOADER" 'HOOK_STATUS_URI=content://ru.big.town.restoremode.restoremodecontentprovider'
require "$LOADER" 'HOOK_STATUS_METHOD=publishHookStatusV1'
require "$PROVIDER" 'Binder.getCallingUid() != 0'
require "$PROVIDER" '.putString(HookStatusContract.PAYLOAD_KEY, arg)'
require "$PROVIDER" '.commit();'
require "$CONTRACT" 'MAX_PAYLOAD_LENGTH = 2_048'
require "$CONTRACT" 'parts.length != 3 + HOOK_IDS.length'
require "$CONTRACT" 'AUTHORITY = "ru.big.town.restoremode.restoremodecontentprovider"'
require "$CONTRACT" 'METHOD_PUBLISH = "publishHookStatusV1"'
require "$APP_MANIFEST" 'android:authorities="ru.big.town.restoremode.restoremodecontentprovider"'
require "$LAYOUT" 'android:id="@+id/textHookStatus"'
require "$ACTIVITY" 'HookStatusContract.renderForUi(hookPayload, BuildConfig.IS_FULL)'
require "$ACTIVITY" 'activityResumed && currentSection == 6'
require "$ACTIVITY" 'SYSTEM_METRICS_INTERVAL_MS = 5_000L'

startup_publish_line=$(grep -n '^publish_hook_status running$' "$LOADER" | tail -n1 | cut -d: -f1)
watchdog_loop_line=$(grep -n '^while \[ 1 \]; do$' "$LOADER" | tail -n1 | cut -d: -f1)
[ -n "$startup_publish_line" ] && [ "$startup_publish_line" -lt "$watchdog_loop_line" ] \
    || fail "initial status must be published before the injection watchdog loop"

for installer in "$FULL_INSTALL" "$LIGHT_INSTALL"; do
    require "$installer" 'setprop ctl.stop voyahtune_load'
    require "$installer" 'getprop init.svc.voyahtune_load'
    forbid "$installer" 'pgrep -f'
    forbid "$installer" 'pkill -'
    forbid "$installer" 'signal_hook_runtime'
done

echo "PASS: direct hook install and demand-scoped status contract"
