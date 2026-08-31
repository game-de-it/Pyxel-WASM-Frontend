# Third-party components

pwf bundles and redistributes the following. Their licences apply to the copies
inside the APK as much as to the originals.

None of them are vendored into this repository — `tools/fetch-runtime.sh`
downloads them from their upstream releases at build time.

## Bundled into the APK

| Component | Version | Licence | Notes |
| --- | --- | --- | --- |
| [Pyxel](https://github.com/kitao/pyxel) | 2.9.9 (plus a 2.9.7 layer) | MIT | The wasm wheel, `pyxel.js`, `pyxel.css`, `import_hook.py` and the gamepad images. `pyxel.js` is patched — see below. |
| [Pyodide](https://github.com/pyodide/pyodide) | 314.0.4 | MPL-2.0 | The core distribution, unmodified. |

Additional Pyxel versions and their matching Pyodide releases are downloaded by
the app itself when a game is pinned to one. Those are the same upstream
releases under the same licences.

### Changes to `pyxel.js`

pwf patches four lines so the runtime loads locally instead of from a CDN, and
so the host can put files in place before an app runs. The edits are applied by
[`tools/patch-pyxel-js.py`](tools/patch-pyxel-js.py) and mirrored on the device
by `RuntimeStore.patchPyxelJs`; both are commented with what each edit is for.
Nothing else in Pyxel is modified.

MPL-2.0 (Pyodide) covers the files it applies to and requires that this notice
travel with them, and that the source for those files remains available — it is,
at the release linked above, unmodified.

## Build and runtime dependencies

| Component | Licence |
| --- | --- |
| [androidx.webkit](https://developer.android.com/jetpack/androidx/releases/webkit) | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache-2.0 |

Commons Compress is there for one reason: Pyodide publishes its core
distribution only as `tar.bz2`, and Android has no bzip2 decoder, so a runtime
installed on the device cannot be unpacked without it.

## Games

No games are included. The screenshots in `docs/images/` show games belonging to
their respective authors, and are there to illustrate the interface.
