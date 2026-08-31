# pwf — developer guide

[English](development.md) · [日本語](development.ja.md) · [← back to the README](../README.md)

How the launcher is put together, why it is put together that way, and the
things that turned out to matter. If you only want to use pwf, read the
[user guide](usage.md) instead.

## Contents

- [Build](#build)
- [How it fits together](#how-it-fits-together)
- [Games with a data folder](#games-with-a-data-folder)
- [Updating a game](#updating-a-game)
  - [Let a game do its own audio](#let-a-game-do-its-own-audio)
  - [Taking Pyxel's PCM sounds off the main thread](#taking-pyxels-pcm-sounds-off-the-main-thread)
- [Save data](#save-data)
- [Games built for a web wrapper](#games-built-for-a-web-wrapper)
- [Driving the launcher with a pad](#driving-the-launcher-with-a-pad)
- [Display](#display)
- [Choosing a Pyxel per game](#choosing-a-pyxel-per-game)
- [Adding a folder at once](#adding-a-folder-at-once)
- [Backing saves up](#backing-saves-up)
- [Pictures in the library](#pictures-in-the-library)
- [Runtimes on the device](#runtimes-on-the-device)
- [Runtime updates](#runtime-updates)
  - [Testing an update over `adb`](#testing-an-update-over-adb)
- [Notes for whoever works on this next](#notes-for-whoever-works-on-this-next)

## Build

Needs JDK 17 and an Android SDK with platform 34.

```sh
sh ../tools/fetch-runtime.sh                                   # once: pull + patch the runtime
python3 ../tools/make-bundle.py 1 app/src/main/assets/runtime   # baseline bundle into the APK
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. It is ~18 MB, almost all of
it Pyodide (13 MB) and the Pyxel wheel (4.8 MB), stored uncompressed so the
WebView can stream the wasm.

`assembleDebug` builds `com.pwf.launcher.debug` alongside the release package.

## How it fits together

| Piece | Role |
| --- | --- |
| `MainActivity` | WebView, SAF picker, back key, immersive mode, lifecycle |
| `PwfPathHandler` | Serves `/web/`, `/runtime/<bundle>/`, `/apps/<id>.pyxapp` — nothing else resolves |
| `RuntimeStore` | The runtime bundle and its over-the-air replacement |
| `AppLibrary` | Stored `.pyxapp` files; titles come from the archive's ZIP comment |
| `HostBridge` | The page's only entry point into the app — small JSON messages only |
| `assets/web/index.html` | Library UI, owns the player frame |
| `assets/web/player.html` | Hosts Pyxel; receives app bytes over `postMessage` |

Three ways in: a single `.pyxapp` file, a URL, or a whole game folder. The last
one exists because plenty of games ship as a `.pyxapp` **plus** a data folder
(`pfs.pyxapp` next to `pfs_assets/`) and read that folder at runtime — take only
the archive and the game starts, but its music and voice files are missing.

Two decisions carry most of the behaviour:

- **Bulk data never crosses the JS bridge.** A `.pyxapp` is fetched natively and
  served back to the page from this app's own origin, so neither CORS nor a
  Binder transaction limit is in the picture.
- **The interpreter is never thrown away.** Booting Pyodide costs ~7 s on
  device; swapping the app inside a live interpreter costs ~0.3 s. The player
  frame starts warming as soon as the library appears and stays alive when a
  game is closed.

## Games with a data folder

Plenty of Pyxel games ship as a `.pyxapp` plus a folder of assets it reads at
runtime. A directory of such games holds many archives and many assets folders
side by side, so which folder belongs to which game cannot be inferred — the app
is added first, and its folder is attached afterwards from its own settings.

The gear beside a library card opens that game's settings: the assets folder,
the touch gamepad, what a tap does, and removal. It is a separate control with
a gap between it and the card rather than a corner of it — a near miss should
not start the game. `assets フォルダを追加` picks a folder and folds it
into `filesDir/appdata/<id>/`, **keeping the picked folder's own name as the top
level** — pick `pfs_assets` and the files land at `pfs_assets/bgm/…`, which is
the path the game asks for. Adding is cumulative, so a game with more than one
folder can have each attached in turn.

Those files are **attached, not copied**. Asset folders run large — one game
here ships 355 MB across 1121 files, on top of a .pyxapp that expands to 76 MB —
and Pyodide's filesystem lives in memory, inside a WebView renderer whose JS
heap tops out around 1.6 GB on this device. Copying a folder like that in at
every launch is not affordable.

So each data file becomes a real filesystem node that knows its size but holds
no bytes; a synchronous fetch fills it the first time something reads it.
Directory listings and `stat` stay honest, which matters — one game enumerates
its BGM folder, another reads a manifest — while memory only covers what the
game actually opens. Attaching those 1121 files takes ~550 ms. Pyodide's own
`FS.createLazyFile` aborts, so this is done by hand: `FS.create` for the node,
`usedBytes` for the size, and a `stream_ops.read` that loads on demand. The
listing is served as `[path, size]` pairs so the size is known up front.

Getting those files where a game looks for them takes two placements, because
the working directory moves:

1. Before `pyxel.init()` the working directory is Pyodide's
   `/pyxel_working_directory`, and games commonly read config there.
2. `pyxel.init()` moves it to the folder the `.pyxapp` was extracted into, which
   is a fresh temp directory named at play time.

So the player attaches the data folder in the working directory, and a small
patch over `pyxel.cli._extract_pyxel_app` links it next to the startup script
the moment that directory exists — a symlink, not a copy, or the mirror would
pull every byte through. Pyxel's own lazy fetch cannot stand in for
this: it resolves one missing path at a time and a game that lists a directory
needs the files there already.

The seam the player hooks is patch 4 in `tools/fetch-runtime.sh`.

## Updating a game

A game that gets a new build is normally overwritten in the folder it was
picked from. Adding it again would be busywork, and worse, it would strand the
saves on the old entry — so entries added from a file remember which document
they came from.

Picking a file takes a persistable read grant on it. From then on, every time
the library is listed the launcher stats those documents; when one's size or
timestamp has moved, the archive is read again into the **same** entry. Data
folder, saves, virtual pad and resume setting all stay where they are.

The entry id is therefore the entry's key, not a checksum of what it holds —
`add()` still derives it from the bytes, but `replace()` leaves it alone. What
does move is `rev`, and the player compares it alongside the app id: a warm
interpreter still holding the previous build is not the game being ready to
resume, so a new build always comes up fresh.

Entries added before their source was remembered, or whose file has since
moved, get pointed at one from 歯車 → `.pyxapp を選び直す`; that same pick is
what starts the tracking. If the grant is later revoked or the file deleted,
the stat quietly fails and the entry keeps the build it already has.

Games added from a URL are not tracked this way — re-adding the URL replaces
nothing, it makes a new entry.

### Let a game do its own audio

Pyxel generates audio on the main thread — the Emscripten SDL2 port drives a
`ScriptProcessorNode` from the frame loop where a native build uses a dedicated
audio thread. So anything that blocks the main thread past the audio buffer is
heard as a buzz, and decoding a track through `pyxel.sounds[n].pcm()` costs
~180 ms on a desktop and more on a handheld. Measured on one game's 5.2 MB
track: ~50 ms to read it, ~180 ms to decode it, repeated on every change
because the game hands slot 0 a new file each time.

Reading the audio ahead was tried and taken back out: it removed the 50 ms and
left the 180 ms, for 71 MB held resident. The stall is decode, not I/O.

The way out is not to decode there at all. A game with its own audio engine
looks for a host bridge and only falls back to Pyxel's PCM when it cannot find
one, so the player offers `window.PVNM_AUDIO`:

| method | |
| --- | --- |
| `playBgm(key, volume, loop, start)` | an `<audio>` element; same track means only the volume changes |
| `stopBgm`, `setBgmVolume`, `getBgmCurrentTime`, `isBgmPlaying` | the element's own state |
| `playSe(key, volume, repeat)` | a fresh element per shot, http-cached after the first; eight at a time |
| `stopAllSe` | all of them |

Clips arrive as asset keys — `assets/sounds/bgm/x.mp3` — which are resolved
against an index built while the data folder is attached. Match on `/assets/`,
not on `assets/`: a folder named `<game>_assets` contains the latter and would
resolve every key one segment too late. The data folder is already served over
http, so elements point straight at it and no bytes pass through the
interpreter; the decode happens in the browser, off the main thread.

The bridge is wired into the launcher's own suspend, so a game left behind the
library does not keep playing. What it does not cover is seeking: `start` needs
range requests, which the asset handler does not serve, so resuming mid-track
may begin from zero.

### Taking Pyxel's PCM sounds off the main thread

A game that has no audio bridge of its own still loads clips through
`pyxel.sounds[n].pcm(path)`, and that decode lands on the main thread next to
Pyxel's own audio generation. 歯車 → `音声の再生` → `ブラウザ` routes those
sounds to the page instead. It is per-app and off by default: nothing changes
for a game that is already fine.

The interception is deliberately small, and matches what these games actually
call:

- `pyxel.sounds` is replaced by a proxy whose slots record the path handed to
  `pcm()` rather than decoding it.
- `pyxel.play(ch, slot, …)` checks that record: a slot with a path is played by
  an `<audio>` element, anything else falls through to Pyxel untouched — so
  chiptune, music and every other sound behave exactly as before.
- `pyxel.stop(ch)` and `pyxel.play_pos(ch)` follow the same split. `play_pos`
  matters: it returns `(slot, seconds)` while the element is playing and `None`
  once it ends, which is how a game notices a track finished and starts the
  next one.

A clip in the data folder is played from the URL it is already served on; one
inside the .pyxapp is read out of the interpreter's filesystem once and kept as
a blob. The patch installs on the first launch that asks for it and is gated by
a flag afterwards, so a game launched without the option gets the interpreter
as it found it.

**Return `undefined` from JavaScript, never `null`.** Pyodide turns `undefined`
into `None`, but `null` into a `JsNull` object — which is not `None`, so a
`pos is None` check reads it as "still playing" and the next `float()` raises.
That is not a silent bug: a game that guards its update loop with
`except Exception` swallows the error and quietly stops advancing, which looked
exactly like the end of a track never being detected while choosing a track by
hand still worked.

What it does not do: `total_sec` and the other queries on an intercepted slot
answer from a sound that was never loaded; `sec` seeking needs range requests
the asset handler does not serve; `resume` and `tick` are ignored for
intercepted sounds. Hence experimental, and hence off by default.

## Save data

Pyodide's filesystem dies with the page, so anything a game writes has to be
copied out and put back.

At extraction time the player records what is in the working directory and in
the extracted app directory. Anything that appears there afterwards — or whose
size or mtime changed — is the game's own writing. That set is handed to the
native side (one file per bridge call, 512 KB each at most) and stored under
`filesDir/appsave/<id>/`; on the next launch it is written back alongside the
data folder, before the app runs.

A snapshot replaces the stored set as a whole, so it also lists files restored
from the previous run that the game did not touch — otherwise they would fall
out of the set. Snapshots happen when a game exits itself, when the player is
closed, before swapping to another app, and when the activity goes to the
background.

## Games built for a web wrapper

Some games are packaged for a vendor's own web page rather than for a plain
Pyxel host. They import `js` to detect "web mode", then hand their BGM, their
virtual pad artwork, vibration and store integration to the hosting page and
read those hooks straight off `window`. Several are read with no guard, so a
missing one is not a missing feature — it is an `AttributeError` before the
title screen.

The player declares them (see the host page API block in `player.html`), and
answers honestly rather than pretending:

| hook | answer |
| --- | --- |
| `isApp` | `false` — there is no store integration here |
| `playAudio(src, loop, startSec)`, `stopAudio`, `pauseAudio`, `resumeAudio`, `preloadAudio` | an `<audio>` element per clip, read out of the Pyodide filesystem as a blob |
| `setVolume` / `setAudioVolume` | element volume |
| `isAudioPlaying`, `isAudioReady(src)`, `getAudioPosition` | element state |
| `hiddenCount` | how many times the game has been put away — the games poll it to notice their music was cut |
| `userGestureCount` | taps and key presses seen, which is how a page earns autoplay |
| `vibrateDevice` | `navigator.vibrate` |
| `useVirtualGamePad` | whether the painted pad is actually on screen |
| `requestReview`, `setButtonImage` | no-ops |
| `setOrientationLock`, `clearOrientationLock`, `isOrientationLockSupported` | `false` — the activity owns rotation |
| `setDisplayMode` | throws, which the games read as unsupported — pyxel owns the canvas |

Clip paths arrive relative to the app folder, which is the working directory
pyxel leaves the game in. Clips are cached per game and dropped on the next
launch, since the same relative path means a different file in another game.

Two consequences worth knowing. A game that treats "web mode" as its trial
build will run as the trial here, because `js` is importable under Pyodide —
that is the game's own gate, not something the launcher can or should route
around. And BGM delivered this way is HTML audio, so it is paused and resumed
with the page rather than by SDL2.

## Driving the launcher with a pad

The device is a handheld, so reaching for the screen to start a game is the odd
part. The shell reads the pad itself.

- **D-pad / left stick** move, **A** activates, **B** goes back — the same thing
  the system back button does, so a sheet closes before the player does.
- Movement is **spatial, not document order**: the library is a grid of
  card-and-gear pairs, and document order runs across it in a way nobody would
  predict. Each press picks the nearest target in that direction, and two rules
  make that behave:
  - **Edge to edge, not centre to centre.** A row is a wide card beside a small
    gear; by centres the card looks hundreds of pixels away and loses to
    whatever happens to sit nearer, when it is in fact right next door.
  - **Anything that lines up beats anything that does not**, however near, and
    when nothing lines up and nothing is close off to the side, focus stays put.
    Otherwise pressing left from a control with nothing to its left lands on
    whatever sits far away at the bottom of the page.
- Cards are `div`s, which a pad cannot reach and a keyboard cannot press, so
  they carry `tabindex` and `role="button"` and answer Enter and Space.
- The focus ring appears only once a pad has been used and goes away on the next
  touch: it is noise for a touch user and the only way to see where you are with
  a pad.
- Opening a sheet moves focus into it; closing one puts focus back on the gear
  it was opened from.
- **While a game is up the launcher ignores the pad entirely** — the game owns
  it. The system back button is still the way out.

Some handhelds deliver their d-pad as key events rather than through the Gamepad
API, so arrows, Enter/Space and Escape/Backspace do the same work. That is also
what makes the whole thing testable over `adb shell input keyevent`.

**The tap gate answers to the pad too.** Pyxel will not start until a `click` or
`touchstart` reaches `document.body` — a gesture the pad cannot produce — so
while its prompt is on screen the player treats any button or key as that press
and dispatches the click itself, then resumes the audio context in case SDL2
opened it suspended. The gate exists so audio starts from a gesture, and the
WebView is already configured not to require one, so standing in for it costs
nothing.

Starting a game hands focus to the frame. Without that the shell keeps a focused
card that Enter would press a second time, and a gamepad is only readable by the
document that holds focus — so the player would never see the button that was
meant to open the gate.

That focus hand-off has a consequence worth knowing before someone files it as a
bug: **started from the pad, the gate is never seen at all.** While the library
is up the shell holds focus, so the frame reads no gamepad and the watcher arms
itself; the moment the frame takes focus it can read the pad, and the button
that chose the game is still physically down. One press does both jobs. It is
not a fabricated gesture — the user really did press a button to start the game,
it just arrived in the other document — and it cannot fire on its own, because
the only moment a frame takes focus with a button held is a launch. Only the
buttons are polled, never the axes, so stick drift cannot trigger it either.

Started by touch the gate still appears once, since the tap lands in the shell
and nothing is held afterwards.

## Display

**Pyxel owns the canvas.** Its integer scaling, screen modes and fullscreen each
resize the canvas for themselves, and anything the page imposes on top turns
into a tug of war — fitting the canvas to the game's aspect ratio did buy a
black margin, but it broke pixel-perfect and fullscreen, so it is gone.

The one thing the page still has to get right is the unit. A WebView here puts
~2.17 device pixels in a CSS pixel, so Pyxel scaling by a whole number still
lands on fractional device pixels and a game pixel comes out 4 columns wide in
places and 5 in others. `MainActivity` sets `setInitialScale(100)`, making the
two units the same, and the shell zooms its own furniture back up by
`density()`. What Pyxel draws is then what the screen shows.

The margin around the game is Pyxel's, not ours: `BACKGROUND_COLOR: Rgb24 =
0x202224` in `crates/pyxel-core/src/settings.rs`, passed to the shader as a
compile-time constant. There is no runtime setting for it. Black margins are
only possible by sizing the canvas so Pyxel has no margin to draw, which is the
fight described above.

`setInitialScale(100)` has one consequence worth knowing about: `screen.width`
and `screen.height` keep reporting the display in density-independent pixels —
745x486 where the page now counts 1620x1055. Emscripten sizes the canvas from
those numbers when Pyxel goes fullscreen, which put the game at 46% in a corner.
The player redefines those four properties to report the page's own units, so
fullscreen asks for the size the screen actually is.

Pyxel's display switches are reachable from a gamepad, not just a keyboard, so
they will get used on a handheld:

| Combination | Effect |
| --- | --- |
| `Alt+Enter` or `A+B+X+Y+DD` | Fullscreen |
| `Alt+8` or `A+B+X+Y+DL` | Maximum vs integer scaling |
| `Alt+9` or `A+B+X+Y+DR` | Screen mode (crisp / smooth / retro) |
| `Alt+0` or `A+B+X+Y+DU` | Performance monitor |

The touch gamepad costs a fifth of the screen height — pyxel.js reserves it by
setting the canvas to 80%. Each app carries its own choice, in its settings:

| Setting | Behaviour |
| --- | --- |
| `自動` (default) | Shown until a real pad reports itself, then hidden |
| `OFF` | Never shown; the height goes straight back to 100% |
| `ON` | Always shown |

pyxel.js appends the controls from each image's `onload`, so they are not in the
document when the launch command reaches the player's hook. A `MutationObserver`
re-applies the choice when they turn up.

## Choosing a Pyxel per game

A game is not always happiest on the newest Pyxel — audio timing is the usual
reason — so the version is a per-game setting, under 歯車 → `Pyxel バージョン`.
The dropdown lists every 2.x release that ships a wasm build; picking one that
is not on the device fetches it first.

`tools/make-catalog.py` surveys those releases at build time and writes
`assets/pyxel-catalog.json`: for each tag, the wheel's exact filename, its ABI,
and the Pyodide release it was built against. Shipping the survey means the
list is there without a network — only installing one reaches out.

What a version costs depends on its ABI, not on its Pyodide version string:

- **Same ABI as the bundle** (`emscripten_5_0_3`, which is 2.9.6 and up): just
  the wheel and its `pyxel.js`, about 5 MB. It runs on the bundled interpreter.
  314.0.0 and 314.0.4 are the same ABI, so a version pinned to either shares.
- **Anything older**: the matching Pyodide comes too, about 20 MB. Pyodide
  publishes its core distribution only as `tar.bz2` and the platform has no
  bzip2, which is what `commons-compress` is in the build for.

Installed runtimes live in `filesDir/runtime/rt/<version>/`, and are addressed
as `/runtime/<bundle>/rt-<version>/…`. That path resolves in three steps: the
version's own files, then the bundle's Pyodide — which a same-ABI layer reaches
as `../pyodide` and shares rather than duplicates — then a layer the APK itself
carries. `PYXEL_ALTERNATES` in `tools/fetch-runtime.sh` is what puts one in the
APK; 2.9.7 is there so a second version exists before any download.

`pyxel.js` has to be patched to load a local Pyodide, and a runtime installed
on the device never passes through the build. `RuntimeStore.patchPyxelJs` makes
the same edits as `tools/patch-pyxel-js.py` — keep the two in step. Only the
first two edits are fatal there; the others just make an old build slightly
less capable.

A page holds one interpreter, so picking a game pinned to another version
rebuilds the player frame: that launch is a cold boot (~7 s) rather than the
usual warm swap. Games on the same version still swap warm.

## Adding a folder at once

`フォルダから一括追加` takes every `.pyxapp` sitting directly in a chosen folder.
Only the top level is listed — walking in would mean reading through the assets
folders, which run to a thousand files each — and assets are deliberately not
paired up by name: what one is called is up to whoever built the game, so they
stay on the per-game flow that asks.

Re-running it is the point, not a hazard. Files are matched by **document id**,
not by URI: the same file picked one at a time and reached through a folder has
two different URIs but one id, and matching on the string would add a second
copy of every game already in the library. A file whose size and timestamp are
unchanged is skipped outright, so a second run costs a few stats.

Adding the same archive twice is likewise not a duplicate — the id is the
content — and `add()` now *merges* onto the entry that is already there. It used
to replace it, which silently dropped the data folder count, the pad and resume
choices and the picture. Both `add()` and `replace()` take those fields from
what is actually on disk, so an import is also a chance to put right anything
stale.

## Backing saves up

歯車 → `セーブデータ` writes a game's progress out as a zip and reads it back.
The destination folder is chosen once and remembered; pointing it at the folder
the `.pyxapp` files live in puts the backup where it will be looked for.

Saves live in the app's own storage, which goes away with the app, so this is
the only way to keep them. Two kinds go in:

- the files under `filesDir/appsave/<id>/`, which is what the snapshot mechanism
  collects, and
- **web storage**, as `_pwf_webstorage.json`. Not every game writes files —
  several keep progress in `localStorage` instead, and a backup that skipped
  those would protect nothing for them. The shell shares an origin with the
  player, so it reads the same store the games write to.

Web storage is per-origin, not per-game, so every archive carries all of it.
That makes any single export a full backup of the storage side, which is worth
knowing before restoring one over a device that has newer progress in another
game.

## Pictures in the library

Cards show a frame from the last time the game ran, and `タイル表示` switches
between **one title per row** and a three-across grid of tiles with the name
beneath. The choice is remembered in `localStorage`.

The bottom bar floats over the page, so focusable items carry
`scroll-margin-bottom`. Without it `scrollIntoView({block: "nearest"})` counts a
row hidden behind the bar as visible and simply does not scroll, which stops the
pad short of the end of the library.

歯車 → `サムネイル` → `削除して撮り直す` throws the picture away when it caught
the wrong moment; the next run takes a new one. The entry keeps an empty `shot`
rather than dropping the field, which is why the button reads the value as a
string and treats empty as none.

A picture is taken **only when the card has none**. Overwriting it on every
launch would mean whatever happened to be on screen six seconds in, and would
leave the 撮り直す button with nothing to do. Within the session that does take
one, later frames replace earlier ones, so a loading screen gives way to the
game itself.

The frame is taken **natively**, with `PixelCopy` on the window, a few seconds
in and then every twenty. Reading the canvas from the page does not work here:
Pyxel draws through WebGL, whose buffer is empty again by the time any script
could read it, and a callback added at page load runs *before* Emscripten's draw
rather than after it, so it only ever sees black. Forcing `preserveDrawingBuffer`
would fix that and cost frame rate on a device where frame rate is the whole
problem.

## Runtimes on the device

The settings sheet lists every Pyxel that is here. What came in the APK cannot
be removed; anything fetched since has a delete button, which arms on the first
press — `confirm()` shows no dialog in this WebView and its silent `false` would
leave the button dead.

## Runtime updates

A bundle is Pyodide + the Pyxel wheel + the patched `pyxel.js`, versioned as one
unit — the wheel is built against one Pyodide ABI and a mixed pair does not
boot. `bundle.json` doubles as the OTA manifest.

Publish one:

```sh
python3 ../tools/make-bundle.py 2 /path/to/publish/2
# serve that directory over https; the manifest URL is <dir>/bundle.json
```

In the app, tap the runtime chip, enter the manifest URL, then 更新を確認 →
適用 → ランタイムを読み込み直す.

What the app does with it:

1. Downloads to a scratch directory, verifying every file against its sha256.
   Files whose hash already exists on the device are copied locally instead —
   a Pyxel-only update moves the wheel and nothing else.
2. Swaps directories with a rename, so a half-applied update is not a state the
   app can end up in, and marks the bundle unconfirmed.
3. Clears that mark once Pyodide actually comes up. A bundle that fails to boot
   is rolled back on the next load — to the previous generation, or to the APK
   baseline, which is never removed.

Reads resolve "downloaded bundle first, APK baseline second", so the app works
even if an update has never succeeded.

### Testing an update over `adb`

The loopback exception in `res/xml/network_security_config.xml` exists for this;
every other host must be https.

```sh
adb reverse tcp:8899 tcp:8899
python3 -m http.server 8899 -d /path/to/publish/2
# manifest URL on the device: http://127.0.0.1:8899/bundle.json
```

## Notes for whoever works on this next

- Avoid `adb install -r` / `am force-stop` in a tight loop. Killing the app takes
  its bound WebView renderer down abnormally; two of those inside a minute and
  Android blacklists the renderer process ("process is bad") until the package
  name changes or the device reboots. It looks exactly like an app crash and is
  not one.
- `window.prompt` is inert in a WebView unless `onJsPrompt` is implemented; the
  shell uses its own dialog.
- A game that exits itself — an in-game QUIT, or Pyxel's quit key — is detected
  by watching Emscripten's `MainLoop.func` go null, which is the only honest
  signal available; the shell then returns to the library.
- After a Python traceback, `resetPyxel()` aborts the wasm module. The player
  reports the interpreter as tainted instead and the shell builds a fresh frame.
- Leaving a game does not stop it: the interpreter is kept warm on purpose. So
  "is a game on screen" is tracked explicitly, and both the audio context and
  Emscripten's main loop follow it. Resuming from another app must not restart
  the sound just because the page became visible — the library may be showing.
- That paused game is also why a tap on its card picks it back up by default
  rather than starting over: a back press should not cost progress. The player
  only resumes when the same app is still loaded and its main loop is alive, so
  a game that exited itself, or died with an error, launches afresh. Each app
  can be set to `最初から` in its settings instead.
- Don't call `WebView.pauseTimers()`. It is process-wide and starves SDL2's
  audio callback mid-buffer, which the device plays back as a held tone. The
  page suspends its own `AudioContext` on the visibility change instead.
