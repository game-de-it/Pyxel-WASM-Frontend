#!/bin/sh
# Fetch and patch the offline Pyxel/Pyodide runtime into prototype/vendor/.
# Everything it writes is reproducible, so vendor/ does not need to be committed.
set -eu

# Overridable, so the same script can lay down a second tree for comparing one
# Pyxel against another:
#   PYXEL_VERSION=v2.9.5 PYXEL_WHEEL=… PYODIDE_VERSION=0.29.3 \
#   PYXEL_ALTERNATES= VENDOR_DIR=prototype/vendor-2.9.5 sh tools/fetch-runtime.sh
PYXEL_VERSION=${PYXEL_VERSION:-v2.9.9}
PYXEL_WHEEL=${PYXEL_WHEEL:-pyxel-2.9.9-cp311-abi3-emscripten_5_0_3_wasm32.whl}
PYODIDE_VERSION=${PYODIDE_VERSION:-314.0.4}

# Alternate Pyxel layers, selectable per game. Only versions built against the
# same wheel ABI as PYXEL_VERSION belong here: those share the bundled Pyodide,
# so a layer costs one wheel rather than a whole second interpreter. Check the
# tag's wasm/ directory before adding one.
PYXEL_ALTERNATES=${PYXEL_ALTERNATES-"v2.9.7:pyxel-2.9.7-cp311-abi3-emscripten_5_0_3_wasm32.whl"}

root=$(cd "$(dirname "$0")/.." && pwd)
vendor="${VENDOR_DIR:-$root/prototype/vendor}"

fetch_pyxel() {  # tag wheel dir
  tag=$1
  wheel=$2
  dir=$3
  src="https://raw.githubusercontent.com/kitao/pyxel/$tag/wasm"
  echo "==> pyxel $tag -> $(basename "$dir")"
  mkdir -p "$dir/images"
  for f in pyxel.js pyxel.css import_hook.py "$wheel"; do
    curl -sSL -o "$dir/$f" "$src/$f"
  done
  for f in pyxel_logo_76x32.png touch_to_start_114x14.png click_to_start_114x14.png \
           gamepad_cross_98x98.png gamepad_button_98x98.png gamepad_menu_92x26.png \
           pyxel_icon_64x64.ico; do
    curl -sSL -o "$dir/images/$f" "$src/images/$f"
  done
}

fetch_pyxel "$PYXEL_VERSION" "$PYXEL_WHEEL" "$vendor/pyxel"
for alt in $PYXEL_ALTERNATES; do
  tag=${alt%%:*}
  fetch_pyxel "$tag" "${alt#*:}" "$vendor/pyxel-${tag#v}"
done

echo "==> pyodide $PYODIDE_VERSION (core distribution)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -sSL -o "$tmp/core.tar.bz2" \
  "https://github.com/pyodide/pyodide/releases/download/$PYODIDE_VERSION/pyodide-core-$PYODIDE_VERSION.tar.bz2"
tar xjf "$tmp/core.tar.bz2" -C "$tmp"
mkdir -p "$vendor/pyodide"
# Browser runtime only; the node CLI and typings are dead weight in an APK.
for f in pyodide.js pyodide.mjs pyodide.asm.js pyodide.asm.mjs pyodide.asm.wasm \
         python_stdlib.zip pyodide-lock.json package.json; do
  [ -f "$tmp/pyodide/$f" ] && cp "$tmp/pyodide/$f" "$vendor/pyodide/$f"
done
true

echo "==> patching pyxel.js for offline use"
for dir in "$vendor"/pyxel "$vendor"/pyxel-*; do
  [ -f "$dir/pyxel.js" ] || continue
  python3 "$root/tools/patch-pyxel-js.py" "$dir/pyxel.js"
done

[ -n "${SKIP_SAMPLES:-}" ] || echo "==> sample apps"
if [ -z "${SKIP_SAMPLES:-}" ]; then
mkdir -p "$root/prototype/apps"
for a in megaball 30sec_of_daylight; do
  curl -sSL -o "$root/prototype/apps/$a.pyxapp" \
    "https://raw.githubusercontent.com/kitao/pyxel/$PYXEL_VERSION/python/pyxel/examples/apps/$a.pyxapp"
done
fi

echo "done: $vendor ($(du -sh "$vendor" | cut -f1))"
