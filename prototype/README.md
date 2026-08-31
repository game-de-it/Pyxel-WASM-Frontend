# pwf prototype

The same web layer the Android app hosts, running in a desktop browser. It is
the quickest way to try a change, and the only convenient way to compare one
Pyxel version against another with a console open.

[← back to the README](../README.md) · [使い方](../docs/usage.ja.md) · [developer guide](../docs/development.md)

## Run

```sh
sh ../tools/fetch-runtime.sh          # once: downloads and patches vendor/
python3 -m http.server 8777 -d .
```

Then open <http://127.0.0.1:8777/>. A server is required — `file://` blocks the
`fetch`/XHR that Pyodide and Pyxel rely on.

## Layout

| Path | Role |
| --- | --- |
| `index.html` | Shell: picks an app, owns the player frame, switches runtimes |
| `player.html` | Player frame: hosts Pyxel, receives app bytes over `postMessage` |
| `vendor/pyodide/`, `vendor/pyxel/` | The runtime, served locally |
| `vendor-<version>/` | A second Pyxel to compare against, if fetched |
| `apps/` | `.pyxapp` files to try. Not in the repository — put your own here |
| `appdata/<name>/` | A game's assets folder, if it has one |

## Comparing two Pyxel versions

```sh
PYXEL_VERSION=v2.9.5 \
PYXEL_WHEEL=pyxel-2.9.5-cp310-abi3-emscripten_4_0_9_wasm32.whl \
PYODIDE_VERSION=0.29.3 \
PYXEL_ALTERNATES= SKIP_SAMPLES=1 VENDOR_DIR="$PWD/vendor-2.9.5" \
sh ../tools/fetch-runtime.sh
```

The shell then offers both under `RUNTIME`. Switching throws the frame away, so
click the game again afterwards.

## Attaching an assets folder

`appdata/<name>/` is served as the game's data folder, and the player attaches
its files lazily, exactly as the app does. It needs a listing beside them:

```sh
mkdir -p appdata/MyGame
ln -s /path/to/MyGame_assets appdata/MyGame/MyGame_assets
python3 - <<'PY'
import json, os
root, out = "appdata/MyGame", "appdata/MyGame/.pwf-index.json"
rows = []
for dirpath, _dirs, names in os.walk(root, followlinks=True):
    for name in names:
        if name.startswith("."):
            continue
        path = os.path.join(dirpath, name)
        rows.append([os.path.relpath(path, root).replace(os.sep, "/"),
                     os.path.getsize(path)])
open(out, "w").write(json.dumps(sorted(rows)))
PY
```

Then add `"MyGame.pyxapp": "appdata/MyGame"` to `DATA` in `index.html`.

Keep the folder name the game expects: it looks for `/assets/` in the path, and
a folder called `<game>_assets` is what puts it there.

## What it establishes

- The runtime works with no network at all: Pyodide is served from `vendor/`.
- A `.pyxapp` is handed to the player as bytes, never as a URL, so neither CORS
  nor the `file:` scheme is involved.
- Booting Pyodide costs ~6-8 s; swapping the app inside a booted interpreter
  costs ~100 ms, so the shell keeps one warm player frame alive.
