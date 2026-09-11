# Changelog

[English](CHANGELOG.md) · [日本語](CHANGELOG.ja.md)

Notable changes in each release. Releases and their APKs are on the
[releases page](https://github.com/game-de-it/Pyxel-WASM-Frontend/releases).

## 0.1.1 — 2026-09-11

### Added

**Installing the Python modules a game needs.** Some games use a module beyond
Pyxel itself — the pymunk physics engine, for instance. pwf ships Python and
its standard library and nothing more, so those games stopped on the line that
asked for the module, and there was no way to give it to them.

- ⚙ → **Python パッケージ** → **モジュールを管理** opens a screen for that game
  with one module per row: what it loads, what a scan says is missing, and what
  is already on the device for it.
- The scan reads the game's own source to see which modules it asks for, then
  checks which of those are actually absent. A game may ask for a module only
  if it happens to be there and carry on without it, and those names are in the
  list too — so fetch the one the error named rather than everything offered.
- Modules are downloaded by pwf itself and served to the game from inside the
  app. The page that runs your games still never loads anything from the
  internet directly.
- Modules are kept separately for each Pyxel version you set on a game. A
  different Pyxel version means a different version of Python running the game,
  and a module only loads into the version of Python it was built for. The
  management screen names the Pyxel and Python version it is working against,
  and says when a module has none for that version yet — scanning again fetches
  the right one.
- A module you have fetched stays on the device. Another game that uses the
  same one can switch it on without downloading it again.

**A readable panel when a game stops.** Until now a game that failed showed its
error only on its own screen, in text far too small to read on a handheld. It
is now shown at a readable size, and the missing modules can be looked up
straight from that panel.

### Fixed

- 仮想コントローラ `自動` now behaves the way the setting says. After playing a
  game with the on-screen controller set to `OFF`, starting another game set to
  `自動` left the controller missing from the screen. Note that a browser does
  not notice a real controller until it has been used, so under `自動` the
  on-screen controller can still appear for the first moments and disappear
  once the real one is noticed.

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
