#!/bin/sh
# Builds compact release copies of the Frida agents without changing the readable sources in Git.
# The pinned esbuild version is deliberately invoked through npx: no platform-specific binary is
# checked into the repository, while a CI/release host gets identical minification semantics.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
SOURCE="$ROOT/Packaging/inject"
OUTPUT=${1:?usage: minify_inject_scripts.sh OUTPUT_DIRECTORY}

[ -d "$OUTPUT" ] || { echo "Output directory does not exist: $OUTPUT" >&2; exit 1; }
command -v npx >/dev/null 2>&1 || {
    echo "npx (Node.js) is required to build compact hook scripts." >&2
    exit 1
}

HOOKS="vd_bypass.js steeringwheelkeys.js launcherdock.js multidisplay.js apollo_tech.js keyboard_lock_en.js keyboard_ru.js instrumentcard.js app_client.js"
ENTRIES=""
for hook in $HOOKS; do
    source="$SOURCE/$hook"
    [ -f "$source" ] || { echo "Missing hook source: $source" >&2; exit 1; }
    ENTRIES="$ENTRIES $source"
done

# Bundle shared pure modules into each self-contained Frida entry point. Installers still receive
# the same flat hook filenames and do not need a module loader on the head unit.
# shellcheck disable=SC2086 # Entry paths are repository-controlled and contain no spaces.
npx --yes esbuild@0.25.0 $ENTRIES --bundle --minify --format=iife --target=es2018 \
    --legal-comments=none --log-level=warning --outdir="$OUTPUT"
for hook in $HOOKS; do
    node --check "$OUTPUT/$hook"
done
