# Changelog

[English](CHANGELOG.md) · [日本語](CHANGELOG.ja.md)

Notable changes in each release. Releases and their APKs are on the
[releases page](https://github.com/game-de-it/Pyxel-WASM-Frontend/releases).

## 0.1.1 — 2026-09-11

### Added

**Python packages a game needs.** The bundled Pyodide is the core distribution:
an interpreter and the standard library, nothing else. A game that imports
pymunk or numpy used to die on that import with no way to ask for what it
wanted.

- ⚙ → **Python パッケージ** → **モジュールを管理** opens a screen for that game
  with one module per row: what it loads, what a scan says is missing, and what
  is already on the device for it.
- The scan reads the game's imports out of the source — running it is what
  fails — and asks the interpreter which of them it cannot find. Names the game
  only tries opportunistically show up too, so fetch the one the error gave.
- Wheels come from the Pyodide distribution or PyPI, fetched natively and
  served from the app's own origin. The WebView still never loads an outside
  URL.
- A package already on the device can be switched on for a game without
  downloading it again.
- A game that stops on a missing import now shows a readable error panel with
  the scan on it, instead of a traceback painted in four-pixel text.

### Changed

- Packages follow the game's **Pyxel version**. An older Pyxel runs on an older
  Python, and a wheel built for one will not load in the other, so each
  interpreter keeps its own copy. The management screen names the Pyxel, Python
  and ABI it is working against, and says when a module has no build for the
  version currently selected.
- Wheels fetched by 0.1.0 are migrated on first start into the bundled
  runtime's ABI, which is the only one they can have been built for.

### Fixed

- 仮想コントローラ `自動` decides from whether a real pad is there, at every
  launch. One page runs every game, so the answer used to carry over: a game
  set to `OFF` left the pad hidden for the next game set to `自動`. What
  remains is the browser's own rule — a gamepad is not revealed until it has
  been used — so under `自動` a painted pad can still appear for the first
  moments and go when the real one is seen.
- The scan no longer offers `math` and the other modules compiled into the
  interpreter. They appear in no file, and PyPI has an unrelated project called
  `math`.

## 0.1.0 — 2026-08-31

First release.

- Pyodide and the Pyxel wasm runtime bundled into the app and served from a
  virtual origin: no network, no browser, no server.
- A library — games added one at a time, a folder at once, or from a URL, with
  the source file tracked so a newer build is picked up on its own.
- Assets folders attached lazily, so hundreds of megabytes cost nothing at
  launch.
- Saves kept between runs and exportable as a zip, including the browser
  storage some games use instead of files.
- Controller support across the launcher as well as the games.
- A Pyxel version per game, from the whole 2.x line, fetched on demand.
- Runtime updates over the network: verified, atomic, rolled back on their own.
