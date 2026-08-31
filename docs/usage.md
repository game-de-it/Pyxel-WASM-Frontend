# pwf — user guide

[English](usage.md) · [日本語](usage.ja.md) · [← back to the README](../README.md)

pwf runs Pyxel games on Android. No browser, no server, nothing to sign in to:
you put `.pyxapp` files on the device and press play.

![The library](images/library-list.png)

## Getting games in

Three ways, all at the bottom of the library:

| | |
| --- | --- |
| **.pyxapp を追加** | Pick one file. |
| **フォルダから一括追加** | Pick a folder; every `.pyxapp` sitting directly in it is added. Assets folders inside it are ignored — see below. |
| **URL から追加** | Fetch one over the network. |

Adding the same game twice is not a problem. pwf recognises a game by its
contents, so a second add lands on the entry that is already there and keeps its
settings, its data folder and its picture.

### Games that ship a folder beside the .pyxapp

Some games come as `thegame.pyxapp` plus a folder of assets. pwf cannot guess
which folder belongs to which game — what it is called is up to whoever built
the game — so you tell it: open the game's ⚙ and use **assets フォルダを追加**.

Big folders are fine. Files are attached rather than copied, and their contents
are only read when the game actually asks for them, so a folder of several
hundred megabytes costs almost nothing at launch.

### Keeping games up to date

When you pick a file, pwf remembers where it came from. Overwrite that file with
a newer build and the next time you open the library it is picked up
automatically — same entry, same saves, same settings. Nothing to re-add.

Games added before this existed, or whose file has moved, can be pointed at one
from ⚙ → **.pyxapp を選び直す**. That first pick is also what starts the
tracking.

Games added from a URL are not tracked this way.

## Playing

Tap a card, or select it with the controller and press A. The first launch after
opening pwf takes a few seconds while the runtime starts; after that, switching
between games is close to instant.

![A game running](images/playing.png)

Leave a game with the **back button**. By default the game keeps running behind
the library, so tapping the card again picks up exactly where you left off — an
accidental back press costs nothing. If you would rather always start from the
beginning, ⚙ → **タップしたとき** → **最初から**.

Games that quit themselves — an in-game QUIT, or Pyxel's own quit key — return
to the library on their own.

## Using a controller

The launcher itself is driven by the pad, not just the games.

| | |
| --- | --- |
| D-pad / left stick | Move |
| A | Select |
| B | Back — closes a settings sheet first, then the game |

The focus outline only appears once you have used the pad, and goes away again
when you touch the screen.

The **TOUCH TO START** screen that Pyxel shows takes a button press too, so a
game can be started from the pad without reaching for the screen. Because the
button that chose the game is usually still held when the game frame appears,
you will often not see that screen at all.

While a game is up the launcher ignores the pad completely — it belongs to the
game. The back button is the way out.

## Per-game settings

Everything below is per game, from the ⚙ beside its card.

![Game options](images/game-options.png)

| Setting | What it does |
| --- | --- |
| **データフォルダ** | Attach or remove the assets folder that belongs to this game. |
| **更新** | Whether pwf is tracking the file this game came from, and a way to point it at one. |
| **セーブデータ** | Write this game's progress out as a zip, or read one back. See below. |
| **サムネイル** | Delete the card's picture so a fresh one is taken next time you play. |
| **Pyxel バージョン** | Which Pyxel this game runs on. See below. |
| **音声の再生** | `Pyxel` (normal) or `ブラウザ`. See below. |
| **仮想コントローラ** | `自動` hides the painted pad once a real one is used; `OFF` and `ON` decide it outright. |
| **タップしたとき** | `続きから` or `最初から`. |
| **ライブラリから削除** | Removes the entry, its data folder and its saves. Press twice. |

### Backing up saves

Saves live inside the app, which means they disappear if the app does. **書き出す**
writes them out as a zip; **読み込む** puts one back.

The destination folder is chosen once and remembered. Choosing the folder your
`.pyxapp` files live in keeps the backup somewhere you will look for it.

Two things go into the archive: the files a game writes, and the browser storage
some games use instead. That second part is shared by every game rather than
being per-game, so **any** export is a full backup of it — and restoring one
puts every game's stored progress back, not just this game's. Worth knowing
before restoring an old archive over newer progress somewhere else.

### Choosing a Pyxel version

Every game runs on the Pyxel that ships with pwf unless you say otherwise. If a
game misbehaves — audio being the usual reason — the dropdown lists every 2.x
release, and picking one that is not on the device downloads it first.

Versions built for the bundled interpreter cost about 5 MB; older ones bring
their own and cost about 20 MB. The list says which is which before you pick.

A game on a different version starts cold (a few seconds) rather than switching
instantly. Games on the same version still switch instantly.

Installed versions can be removed later from the runtime settings.

### Audio

Pyxel generates its sound on the same thread that runs the game, so a game that
loads music while playing can be heard doing it — a short buzz as a track
changes. Setting **音声の再生** to **ブラウザ** hands those sounds to the browser
instead, which decodes them out of the way.

It is off by default and only worth reaching for if you hear that. It does not
apply to every game: some bring their own audio handling and never go through
Pyxel's, and those are unaffected either way.

## The library

**タイル表示 / リスト表示** switches between one game per row and a grid of
tiles. The choice is remembered.

![Tiles](images/library-tiles.png)

Cards show a frame from the game, taken a few seconds into the first session
after a card has no picture. It is not replaced on later launches — if it caught
a loading screen, delete it from ⚙ → **サムネイル** and play again.

## Runtime settings

The chip at the top right opens them.

![Runtime settings](images/runtime-settings.png)

- **導入済みランタイム** — every Pyxel on the device. What came with pwf cannot
  be removed; anything downloaded since can be, to reclaim the space.
- **更新元 manifest** — a URL to check for a newer runtime, if you are hosting
  one. Leave it empty otherwise.
- **前のバンドルへ戻す** — undo a runtime update.

A runtime that fails to start is rolled back automatically on the next launch.

## When something is wrong

**A game will not start.** Some games are built for a particular web page and
expect it to provide things pwf does not. Most of those are handled, but if a
game fails immediately it is worth reporting with the game's name.

**Crackle or a buzz in the sound.** Try **音声の再生 → ブラウザ**, and if that
does not help, a different **Pyxel バージョン**. Some of this is inherent to
running Pyxel in a browser engine and cannot be fixed from here — see the
[developer guide](development.md#let-a-game-do-its-own-audio).

**The picture is not what you wanted.** ⚙ → **サムネイル** → delete, then play
again.

**A game does not see its assets.** Check ⚙ → データフォルダ says a file count
rather than なし, and that you attached the folder itself rather than something
inside it.

**Progress disappeared.** Saves are keyed to the game. Removing an entry removes
its saves with it; re-adding the same file gets them back only if the entry was
never removed. Export anything you care about.
