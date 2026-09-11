# pwf

**Run Pyxel games on Android. No browser, no server, no network.**

[English](README.md) · [日本語](README.ja.md)

[Pyxel](https://github.com/kitao/pyxel) can already run in a browser, and that
is how most `.pyxapp` games are shared. pwf takes the same runtime — Pyodide and
the Pyxel wasm wheel — bundles it into an Android app, and puts a library in
front of it. Put `.pyxapp` files on the device, press play.

![The library](docs/images/library-list.png)

## What it does

- **Works offline.** Pyodide and the Pyxel wheel ship inside the APK and are
  served from a virtual origin. The WebView never loads an outside URL.
- **A library.** Add games one at a time, a whole folder at once, or from a URL.
  Overwrite a `.pyxapp` with a newer build and it is picked up on its own —
  same entry, same saves.
- **Games with an assets folder.** Attach the folder that belongs to a game;
  hundreds of megabytes cost nothing at launch, because files are read only when
  the game asks for them.
- **Saves that survive.** Progress is kept between runs, and can be written out
  as a zip and read back — including the browser storage some games use instead
  of files.
- **A controller drives everything**, the launcher included, down to the
  *touch to start* screen.
- **A Pyxel version per game**, from the whole 2.x line, downloaded on demand
  when a game only behaves on a particular one.
- **Python packages on demand.** A game that imports pymunk or numpy says so in
  a readable error, and one button finds the wheel, its dependencies, and
  switches it on for that game.
- **Runtime updates over the network**, verified, atomic, and rolled back on
  their own if they fail to start.

## Requirements

Android 8.0 (API 26) or newer. The real constraint is the WebView rather than
the OS: Pyodide needs a reasonably current engine, and the WebView updates
separately from Android. Development and testing were done on Android 14 with
WebView 151.

## Installing

Download the APK from [Releases](../../releases) and install it, or build it:

```sh
sh tools/fetch-runtime.sh                                        # once
python3 tools/make-bundle.py 4 android/app/src/main/assets/runtime
python3 tools/make-catalog.py android/app/src/main/assets/pyxel-catalog.json
cd android && ./gradlew :app:assembleRelease
```

The first script downloads the Pyxel and Pyodide releases the app bundles;
nothing is vendored into this repository.

## Documentation

| | |
| --- | --- |
| [User guide](docs/usage.md) · [使い方](docs/usage.ja.md) | Adding games, playing them, every setting |
| [Developer guide](docs/development.md) · [開発者向け](docs/development.ja.md) | Architecture, build, and why things are the way they are |
| [Changelog](CHANGELOG.md) · [変更履歴](CHANGELOG.ja.md) | What changed in each release |

There is also a [prototype](prototype/README.md) — the same web layer running in
a desktop browser, which is the quickest way to try a change or compare one
Pyxel version against another.

## Licence

pwf is [MIT](LICENSE).

It bundles and redistributes third-party components under their own licences —
Pyxel (MIT) and Pyodide (MPL-2.0). See [THIRD-PARTY.md](THIRD-PARTY.md).

## Acknowledgements

[Pyxel](https://github.com/kitao/pyxel) by Takashi Kitao, and
[Pyodide](https://github.com/pyodide/pyodide). pwf is a launcher around their
work and claims none of it.
