#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
HOOK="$ROOT/Packaging/inject/steeringwheelkeys.js"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/WiperColdService.java"
POLICY="$ROOT/Native/app/src/main/java/ru/big/town/anative/MediaControlPolicy.java"

fail() { echo "door media pause proxy test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}
line_first() { grep -nF -- "$2" "$1" | head -n1 | cut -d: -f1; }

# An ordered receiver must claim a toggle before attempting it. A result-code reset after that point
# would make an ambiguous failure execute the Native fallback and turn PAUSE into PAUSE+PLAY.
reader_guard=$(line_first "$HOOK" 'reader unavailable before claim')
claim=$(line_first "$HOOK" 'this.setResultCode(MEDIA_PROXY_ACK);')
dispatch=$(line_first "$HOOK" 'var handled = nativeQG')
[ "$reader_guard" -lt "$claim" ] && [ "$claim" -lt "$dispatch" ] \
    || fail "proxy claim/dispatch ordering is not at-most-once"
forbid "$HOOK" 'setResultCode(0)'
require "$HOOK" 'Java.choose("com.qinggan.keymanager.service.engine.KeyManagerReader"'
require "$HOOK" 'discoverReader(0);'

# Active OEM/Bluetooth audio without an Android session must follow the original QG6 route used by
# the physical steering-wheel button, not standard key 85 which those sources may ignore.
require "$POLICY" 'static boolean useNativeQingganPausePath('
require "$SERVICE" 'MediaControlPolicy.useNativeQingganPausePath('
require "$SERVICE" 'sendMediaProxy(keyCode, nativeQinggan, am, workGeneration);'

echo "PASS: door pause proxy is at-most-once and preserves the no-session Qinggan route"
