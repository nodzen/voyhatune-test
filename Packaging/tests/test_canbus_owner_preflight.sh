#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
HELPER="$ROOT/Packaging/installer/common/canbus-owner.sh"

adb() {
    case "$*" in
        "shell dumpsys package permissions")
            printf '%s\n' "$CANBUS_TEST_DUMP"
            ;;
        "shell pm list packages --user 0")
            printf '%s\n' "$CANBUS_TEST_PACKAGES"
            ;;
        *)
            echo "unexpected adb call in preflight test: $*" >&2
            return 1
            ;;
    esac
}

. "$HELPER"

CANBUS_TEST_PACKAGES="package:ru.big.town.anative"
CANBUS_TEST_DUMP='Permission [com.qinggan.permission.WRITE_CANBUS]
 sourcePackage=ru.big.town.anative
Permission [android.permission.INTERNET]
 sourcePackage=android'
if ! canbus_owner_preflight full >/dev/null; then
    echo "own WRITE_CANBUS owner must allow an update" >&2
    exit 1
fi

CANBUS_TEST_DUMP='Permission [com.qinggan.permission.WRITE_CANBUS]
 sourcePackage=com.example.canbus
Permission [android.permission.INTERNET]
 sourcePackage=android'
if canbus_owner_preflight light >"${TMPDIR:-/tmp}/voyahtune-canbus-owner-test.$$" 2>&1; then
    echo "foreign WRITE_CANBUS owner must block an install" >&2
    exit 1
fi
if ! grep -qF 'com.example.canbus' "${TMPDIR:-/tmp}/voyahtune-canbus-owner-test.$$"; then
    echo "foreign WRITE_CANBUS owner must be reported" >&2
    rm -f "${TMPDIR:-/tmp}/voyahtune-canbus-owner-test.$$"
    exit 1
fi
rm -f "${TMPDIR:-/tmp}/voyahtune-canbus-owner-test.$$"

CANBUS_TEST_DUMP='Permission [android.permission.INTERNET]
 sourcePackage=android'
if ! canbus_owner_preflight full >/dev/null; then
    echo "missing WRITE_CANBUS declaration must allow a first install" >&2
    exit 1
fi

for installer in "$ROOT/Packaging/installer/full/install.sh" "$ROOT/Packaging/installer/light/install.sh"; do
    grep -qF 'canbus_owner_preflight' "$installer"
    grep -qF 'canbus_prepare_writable_system' "$installer"
done
for installer in "$ROOT/Packaging/installer/full/install.bat" "$ROOT/Packaging/installer/light/install.bat"; do
    grep -qF 'call canbus-owner.bat' "$installer"
done
grep -qF 'pm uninstall --user 0' "$HELPER"
grep -qF '/system/priv-app/VoyahHlCTRL' "$HELPER"
grep -qF 'rm -rf /data/system/package_cache/*' "$HELPER"
grep -qF 'adb reboot' "$HELPER"
grep -qF 'canbus-owner.sh canbus-owner.bat' "$ROOT/make_release.sh"

echo "CANBus owner preflight tests passed"
