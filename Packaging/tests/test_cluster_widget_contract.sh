#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
MULTI="$ROOT/Packaging/inject/multidisplay.js"
DOCK="$ROOT/Packaging/inject/launcherdock.js"
COORDINATOR="$ROOT/Native/app/src/main/java/ru/big/town/anative/AppLaunchCoordinator.java"
CLUSTER="$ROOT/Native/app/src/main/java/ru/big/town/anative/ClusterMediaHostActivity.java"
GEOMETRY="$ROOT/Native/app/src/main/java/ru/big/town/anative/ClusterSurfaceGeometry.java"
WIDGETS="$ROOT/Native/app/src/main/java/ru/big/town/anative/MediaWidgetOverlayService.java"
LEASE="$ROOT/Native/app/src/main/java/ru/big/town/anative/VirtualDisplayLease.java"

fail() { echo "cluster/widget contract failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() { if grep -Fq -- "$2" "$1"; then fail "$1: forbidden $2"; fi; }

node --check "$MULTI"
node --check "$DOCK"
require "$MULTI" 'voyahtune_cluster_gesture'
require "$MULTI" 'voyahtune_cluster_allowed_packages'
require "$MULTI" 'java.lang.String,java.lang.String,int'
require "$MULTI" 'unsupported moveActivity ABI'
require "$MULTI" 'return overload.call(this, packageName, activityName, sourceDisplayId);'
require "$MULTI" '"ru.big.town.anative.OPEN_CLUSTER_APP"'

require "$COORDINATOR" 'ClusterDisplayPolicy.choose(candidates)'
require "$COORDINATOR" 'ClusterDisplayPolicy.chooseOemTaskFallback(tasks)'
require "$COORDINATOR" 'TaskRetirementPolicy.shouldRetire(packageName, targetDisplay, record)'
require "$COORDINATOR" 'awaitTaskRetirement('
require "$COORDINATOR" 'TASK_RETIRE_MAX_ATTEMPTS'
require "$COORDINATOR" 'Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP'
forbid "$COORDINATOR" 'FLAG_ACTIVITY_MULTIPLE_TASK'
require "$CLUSTER" 'new TextureView(this)'
require "$CLUSTER" 'ClusterSurfaceGeometry.forDisplay'
require "$CLUSTER" 'VirtualDisplayLease.acquire(ClusterMediaHostActivity.this'
forbid "$CLUSTER" 'setOnTouchListener'
require "$GEOMETRY" 'new Bounds(66, 200, 574, 464, 160)'

require "$DOCK" 'OPEN_DOCK_ACTION'
ADVANCE="$ROOT/RestoreMode/app/src/main/java/ru/big/town/restoremode/AdvanceActivity.java"
require "$ADVANCE" 'pickDockClusterApp(slot)'
require "$ADVANCE" 'saveDockLongAction(slot, "cluster_app:" + allowed.get(which)'
require "$ADVANCE" 'Сначала добавьте приложение в разделе «Приборка»'

require "$DOCK" 'BigMediaCard.dispatchTouchEvent.overload("android.view.MotionEvent")'
require "$DOCK" 'var result = dispatch.call(this, event);'
require "$DOCK" 'return result;'
require "$WIDGETS" 'removeOverlay(); // OEM card regains its window and all touch/media events.'
require "$WIDGETS" 'VirtualDisplayLease.acquire(MediaWidgetOverlayService.this'
require "$LEASE" 'if (owner != next || generation != ticket) return;'

echo "PASS: cluster and media-widget hooks are allowlisted, fail-closed and single-VD"
