#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
HOOK="$ROOT/Packaging/inject/steeringwheelkeys.js"
SERVICE="$ROOT/Native/app/src/main/java/ru/big/town/anative/WiperColdService.java"
POLICY="$ROOT/Native/app/src/main/java/ru/big/town/anative/MediaControlPolicy.java"
TRANSITION_TEST="$ROOT/Packaging/tests/media_transition_test.mjs"
ROUTER="$ROOT/Native/app/src/main/java/ru/big/town/anative/MediaControlRouter.java"
NOW_PLAYING="$ROOT/Native/app/src/main/java/ru/big/town/anative/NowPlayingService.java"
PROVIDER="$ROOT/Native/app/src/main/java/ru/big/town/anative/NowPlayingProvider.java"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
INSTRUMENT="$ROOT/Packaging/inject/instrumentcard.js"

fail() { echo "door media pause proxy test failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() {
    if grep -Fq -- "$2" "$1"; then
        fail "$1: forbidden $2"
    fi
}
line_first() { grep -nF -- "$2" "$1" | head -n1 | cut -d: -f1; }

node "$TRANSITION_TEST"

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
require "$SERVICE" 'MediaControlRouter.isPinnedTargetReactivated(this)'
require "$SERVICE" 'noop session but active audio, using PLAY_PAUSE path'
forbid "$SERVICE" 'music already active, toggle suppressed'

# A stock BT/DAB/USB click returns arbitration to the OEM source. Neither a persisted app pin nor
# an already-retained WECAR control proxy may keep routing controls into Spotify/Yandex afterward.
require "$PROVIDER" 'METHOD_CLEAR_SOURCE = "clear_source"'
require "$PROVIDER" 'NowPlayingService.clearSourceSelection()'
require "$NOW_PLAYING" 'MediaControlRouter.releaseSourcePin();'
require "$ROUTER" 'static void releaseSourcePin()'
require "$NOW_PLAYING" 'OEM_SOURCE_SELECTED_KEY'
require "$NOW_PLAYING" 'service.current = findBestOemController(controllers);'
require "$NOW_PLAYING" 'new third-party playback released OEM source selection'
require "$DOCK" 'clearBridgeSourceSelection();'
require "$DOCK" 'if (!currentWecar()) return;'
require "$INSTRUMENT" 'if (!currentWecar()) return;'

# App→app and app→BT transitions must have no intermediate NO/DAB frame. The launcher can safely
# re-announce BT after both OEM widgets have attached; the instrument process must leave BT to its
# stock controller because calling MediaManager during MusicBaseView construction crashes this ROM.
require "$DOCK" 'clearNativeSelection(transition.notifyNoMedia);'
require "$INSTRUMENT" 'clearNativeSelection(transition.notifyNoMedia);'
require "$DOCK" 'scheduleNativeBluetoothRefresh();'
require "$INSTRUMENT" 'stock BT_MUSIC refresh retained; no manual MediaManager callback'

echo "PASS: door pause proxy is at-most-once and preserves the no-session Qinggan route"
