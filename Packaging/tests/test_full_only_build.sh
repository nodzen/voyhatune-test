#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)

fail() { echo "Full-only build contract failed: $*" >&2; exit 1; }
require() { grep -Fq -- "$2" "$1" || fail "$1: missing $2"; }
forbid() { if grep -Fq -- "$2" "$1"; then fail "$1: forbidden $2"; fi; }

for gradle in "$ROOT/Native/app/build.gradle.kts" "$ROOT/RestoreMode/app/build.gradle.kts"; do
    forbid "$gradle" 'flavorDimensions'
    forbid "$gradle" 'productFlavors'
done

forbid "$ROOT/make_release.sh" 'assembleFullRelease'
forbid "$ROOT/make_release.sh" 'app-full-release.apk'
require "$ROOT/make_release.sh" 'task="assembleRelease"'
require "$ROOT/make_release.sh" 'outputs/apk/release/app-release.apk'
require "$ROOT/make_release.sh" 'test_full_only_build.sh'

[ ! -d "$ROOT/Packaging/installer/light" ] || fail 'deprecated Light installer still exists'

echo "PASS: build graph has one unflavoured Full product"
