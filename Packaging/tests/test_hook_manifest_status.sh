#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
MANIFEST="$ROOT/Packaging/system/voyahtune-hook-manifest.json"
LOADER="$ROOT/Packaging/system/load.bin"
PROVIDER="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/RestoreModeContentProvider.java"
CONTRACT="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/HookStatusContract.java"
APP_MANIFEST="$ROOT/RestoreMode/app/src/main/AndroidManifest.xml"
ACTIVITY="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
LAYOUT="$ROOT/RestoreMode/app/src/main/res/layout/activity_advance.xml"
FULL_INSTALL="$ROOT/Packaging/installer/full/install.sh"
FULL_INSTALL_BAT="$ROOT/Packaging/installer/full/install.bat"
CANBUS_HELPER="$ROOT/Packaging/installer/common/canbus-owner.sh"

fail() { echo "hook manifest/status test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() { if grep -Fq -- "$2" "$1"; then fail "$1: forbidden $2"; fi; }
line_first() { grep -nF "$2" "$1" | head -n1 | cut -d: -f1; }
sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
    else shasum -a 256 "$1" | awk '{print $1}'; fi
}

[ "$(grep -F -x -c '  "schemaVersion": 1,' "$MANIFEST")" -eq 1 ] || fail "schemaVersion != 1"
[ "$(grep -F -c '{"id":' "$MANIFEST")" -eq 8 ] || fail "manifest must contain eight exact scripts"

check_entry() {
    id=$1 process=$2 script=$3
    line=$(grep -F '"id":"'"$id"'"' "$MANIFEST") || fail "missing id=$id"
    printf '%s\n' "$line" | grep -Fq '"process":"'"$process"'","script":"'"$script"'"' \
        || fail "$id mapping mismatch"
    expected=$(printf '%s\n' "$line" | sed -n 's/.*"sha256":"\([0-9a-f]*\)".*/\1/p')
    actual=$(sha256_file "$ROOT/Packaging/inject/$script")
    [ "${#expected}" -eq 64 ] && [ "$expected" = "$actual" ] || fail "$script SHA mismatch"
}

check_entry vd-bypass system_server vd_bypass.js
check_entry steering-wheel com.qinggan.keymanager.service steeringwheelkeys.js
check_entry launcher-dock com.qinggan.app.launcher launcherdock.js
check_entry multi-display com.qinggan.systemservice multidisplay.js
check_entry apollo-tech com.qinggan.app.vehiclesetting apollo_tech.js
check_entry keyboard-en com.qinggan.app.qgime keyboard_lock_en.js
check_entry keyboard-ru com.qinggan.app.qgime keyboard_ru.js
check_entry instrument-card com.qinggan.instrumentcard instrumentcard.js

for token in \
        'HOOK_SET_VALID=ok' \
        'mv -f "$STATUS_STAGE" "$HOOK_STATUS_FILE"' \
        '/system/bin/content call --user 0' \
        'HOOK_STATUS_PROVIDER_MAX_ATTEMPTS=3' \
        'HOOK_STATUS_URI=content://ru.big.town.restoremode.restoremodecontentprovider' \
        'HOOK_STATUS_METHOD=publishHookStatusV1'; do
    require "$LOADER" "$token"
done
require "$PROVIDER" 'Binder.getCallingUid() != 0'
require "$PROVIDER" '.putString(HookStatusContract.PAYLOAD_KEY, arg)'
require "$PROVIDER" '.commit();'
require "$CONTRACT" 'MAX_PAYLOAD_LENGTH = 2_048'
require "$CONTRACT" 'AUTHORITY = "ru.big.town.restoremode.restoremodecontentprovider"'
require "$CONTRACT" 'METHOD_PUBLISH = "publishHookStatusV1"'
require "$APP_MANIFEST" 'android:authorities="ru.big.town.restoremode.restoremodecontentprovider"'
require "$LAYOUT" 'android:id="@+id/textHookStatus"'
require "$ACTIVITY" 'HookStatusContract.renderForUi(hookPayload)'
require "$ACTIVITY" 'SYSTEM_METRICS_INTERVAL_MS = 5_000L'

startup_publish_line=$(grep -n '^publish_hook_status running$' "$LOADER" | tail -n1 | cut -d: -f1)
watchdog_loop_line=$(grep -n '^while \[ 1 \]; do$' "$LOADER" | tail -n1 | cut -d: -f1)
[ -n "$startup_publish_line" ] && [ "$startup_publish_line" -lt "$watchdog_loop_line" ] \
    || fail "initial status must be published before the injection watchdog loop"

require "$FULL_INSTALL" 'canbus_owner_preflight full'
require "$FULL_INSTALL" 'canbus_prepare_writable_system'
require "$CANBUS_HELPER" 'canbus_remove_hl_service() {'
require "$CANBUS_HELPER" 'rm -rf /data/system/package_cache/*'
full_sh_verity=$(line_first "$FULL_INSTALL" 'if ! canbus_prepare_writable_system; then')
full_sh_barrier=$(line_first "$FULL_INSTALL" 'HOOK_UPDATE_BARRIER_ARMED=1')
full_sh_mutation=$(line_first "$FULL_INSTALL" 'if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then')
[ "$full_sh_verity" -lt "$full_sh_barrier" ] && [ "$full_sh_barrier" -lt "$full_sh_mutation" ] \
    || fail "full install.sh freeze is not after verity reboot and before hook mutation"
require "$FULL_INSTALL" 'setprop ctl.stop voyahtune_load'
require "$FULL_INSTALL" 'setprop ctl.start voyahtune_load'
require "$FULL_INSTALL" 'install_required_data_file voyahtune-hook-manifest.json'
require "$FULL_INSTALL" 'if ! configure_yandex_dns; then'
forbid "$FULL_INSTALL" 'pgrep -f'
forbid "$FULL_INSTALL" 'pkill -'

require "$FULL_INSTALL_BAT" 'call :verify_hook_manifest'
require "$FULL_INSTALL_BAT" 'setprop ctl.stop voyahtune_load'
require "$FULL_INSTALL_BAT" 'setprop ctl.start voyahtune_load'
require "$FULL_INSTALL_BAT" 'call "%~dp0install-yandex-dns.bat" configure'
require "$FULL_INSTALL_BAT" 'Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256'
[ "$(grep -F -c 'call :compute_sha256 ' "$FULL_INSTALL_BAT")" -eq 8 ] \
    || fail "full install.bat must hash exactly eight hook scripts"
forbid "$FULL_INSTALL_BAT" 'pgrep -f'
forbid "$FULL_INSTALL_BAT" 'pkill -'

DNS_INSTALL_SH="$ROOT/Packaging/installer/common/install-yandex-dns.sh"
DNS_INSTALL_BAT="$ROOT/Packaging/installer/common/install-yandex-dns.bat"
require "$DNS_INSTALL_SH" 'configure_yandex_dns'
require "$DNS_INSTALL_SH" 'YDNS_CHANGED'
require "$DNS_INSTALL_BAT" 'choice /C YN /N /M "Install Yandex DNS now? [Y/N]: "'
sh -n "$DNS_INSTALL_SH"

echo "PASS: Full-only atomic hook manifest and demand-scoped status contract"
