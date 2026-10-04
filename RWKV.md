# kuma3-anki: AnkiDroid with on-device RWKV-Instant and FSRS-7

This branch (`rwkv`) is AnkiDroid 2.26.0alpha2 built against the
[JSchoreels FSRS-7 fork of Anki](https://github.com/JSchoreels/anki) (26.09.3+fsrs7), with a
small layer that runs the fork's RWKV recall model on the phone, plus a few deck list extras.
It is a personal build, shared as it is. Nothing here is endorsed by AnkiDroid, Anki, or the
fork's author.

If you are an AI assistant asked to change something: read "Where things live" first, then
"Build" and "Tests". The sections are short on purpose.

## The three repositories

| Repository (branch `rwkv`) | Based on | What we changed |
|---|---|---|
| [kuma3-anki-core](https://github.com/Pedrubik2000/kuma3-anki-core) | JSchoreels/anki `e26738b` | Rust backend and the deck options web page |
| [kuma3-anki-backend](https://github.com/Pedrubik2000/kuma3-anki-backend) | ankidroid `11cc1de` | Build switches; `anki` submodule points to the fork above |
| [kuma3-anki-app](https://github.com/Pedrubik2000/kuma3-anki-app) (this one) | ankidroid `v2.26.0alpha2` | The app |

Every change is a normal commit on top of the upstream commit, one per feature:
`git log upstream-commit..rwkv` in each repository is the complete list.

## What it does

- **RWKV-Instant on the phone.** The desktop fork drives its RWKV model from Python. Here the
  backend does it: once the app hands it the bundled model, it replays the review history and
  scores review cards before a review queue or the deck counts are built. Presets with
  "Use RWKV-Instant to choose review cards" then pick cards by predicted recall, as on the desktop.
  RWKV-Curve (answer intervals) and "reschedule" are not implemented on the phone.
- **FSRS-7 state repair.** Cards that pass through AnkiWeb come back without the fork's `s_int`
  and `s_fast`. The backend recomputes them from the review history after each sync and on open,
  without marking the cards as changed.
- **Deck options page on phones**: saving works, desktop-only buttons are hidden, a Maintenance
  section has "Rebuild RWKV State" and a status line.
- **Deck list extras**: a leaderboard (the desktop "Anki Leaderboard" add-on's server; sign in
  from the menu), a review heatmap (year or month), and the time answered today on each deck row.
- **Custom study** button on the finished-deck screen.

## Where things live

Backend (`kuma3-anki-core`, checked out as `Anki-Android-Backend/anki`):

| File | Role |
|---|---|
| `rslib/src/scheduler/rwkv/offline.rs` | The whole offline layer: runtime, history replay, scoring, the hooks' entry points |
| `rslib/src/scheduler/queue/mod.rs`, `decks/tree.rs`, `scheduler/service/mod.rs`, `scheduler/answering/mod.rs` | One-line hooks that call into `offline.rs` |
| `proto/anki/scheduler.proto` | `RwkvPrepareOffline`, `RwkvOfflineInstantPassStep` and their messages |
| `rslib/src/scheduler/fsrs/memory_state.rs` (`repair_stripped_fsrs_memory_states`), `storage/card/mod.rs`, `sync/collection/normal.rs` | FSRS-7 state repair |
| `ts/routes/deck-options/` (`RwkvOptions.svelte`, `DeckOptionsPage.svelte`, `lib.ts`) | Deck options page changes |
| `rslib/src/bin/rwkv_offline_host.rs` | Test program: runs the layer on a collection copy on a PC |

App (this repository):

| File | Role |
|---|---|
| `anki-common/.../RwkvOffline.kt` | Installs `assets/rwkv/model.bin`, calls `rwkvPrepareOffline` when the collection opens |
| `AnkiDroid/.../leaderboard/Leaderboard.kt` | Leaderboard client: sign-in, stats, upload, parsing the board |
| `AnkiDroid/.../leaderboard/LeaderboardUi.kt` | The board (table, dialogs) and the code that adds rows below the decks |
| `AnkiDroid/.../leaderboard/Heatmap.kt` | Heatmap data, drawing, and its row below the decks |
| `AnkiDroid/.../leaderboard/DeckTimes.kt` | Time answered today per deck |
| `DeckPicker.kt`, `Sync.kt`, `widgets/DeckAdapter.kt`, `pages/CongratsPage.kt`, `pages/PostRequestHandler.kt` | Small hooks into existing screens |

## Build

Linux, or Windows with WSL2 (Ubuntu 24.04). About 8 GB of RAM is enough with the flags below.

Tools: JDK 21, Rust 1.97.1 with target `aarch64-linux-android`, `cargo-ndk` 4.1.2,
[`n2`](https://github.com/evmar/n2) (`bash anki/tools/install-n2`), Android SDK
(`platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`) and NDK 29.0.14206865.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
export ANDROID_ARCH=arm64 SKIP_ROBOLECTRIC=1 CARGO_BUILD_JOBS=4
# optional, for an APK you will share: keep your home folder (and user name) out of the library
export ANDROID_RUSTFLAGS="--remap-path-prefix=$HOME=/build"

# the two folders must be siblings with exactly these names (the build looks for ../Anki-Android-Backend)
git clone --depth 1 https://github.com/Pedrubik2000/kuma3-anki-backend.git Anki-Android-Backend
git -C Anki-Android-Backend submodule update --init --depth 1 anki
git -C Anki-Android-Backend/anki submodule update --init --depth 1 ftl/core-repo ftl/qt-repo
git clone --depth 1 https://github.com/Pedrubik2000/kuma3-anki-app.git Anki-Android
printf 'sdk.dir=%s\nlocal_backend=true\n' "$ANDROID_HOME" > Anki-Android/local.properties

(cd Anki-Android-Backend && RELEASE=1 cargo run -p build_rust)     # backend: Rust + web pages
(cd Anki-Android && ./gradlew :AnkiDroid:assembleFullDebug -PabiFilter=arm64-v8a \
    --max-workers=3 --no-parallel -Pkotlin.daemon.jvmargs=-Xmx2g)
# -> Anki-Android/AnkiDroid/build/outputs/apk/full/debug/AnkiDroid-full-arm64-v8a-debug.apk
```

Stop leftover Gradle daemons (`pkill java`) between the two steps if memory is short.

The APK is a debug build (`com.ichi2.anki.debug`) signed with your machine's debug key. A build
signed with a different key cannot be installed over an existing one: sync, then uninstall first.

## Tests

- **Backend, on a PC**: `cargo build --release -p anki --bin rwkv_offline_host` in `anki`
  (set `PROTOC` to `out/extracted/protoc/bin/protoc` after one full backend build), then
  `rwkv_offline_host <collection copy.anki2> <model.bin> [scores.csv] [answer]`. It replays,
  scores, rebuilds and (with `answer`) answers four cards and compares the incrementally updated
  model state with a fresh replay. Use a copy of a collection, never the live file.
  `rwkv_offline_host <copy> repair` runs the FSRS-7 state repair.
- **App**: `./gradlew :AnkiDroid:compileFullDebugKotlin` compiles; AnkiDroid's pre-commit hook
  runs ktlint (`./gradlew ktlintFormat` fixes most findings).

## Things to know before changing code

- The offline layer must never write card data. It only installs in-memory score maps; the one
  intended write is the revlog kind of same-day repeats.
- `RwkvOfflineInstantPassStep` and the deck options page share generated code: after changing
  `scheduler.proto`, rebuild the backend before the app.
- Deck options, congratulations and statistics are web pages served by the backend
  (`anki/ts/routes`). A method the page posts must be listed in
  `pages/PostRequestHandler.kt`, or the app answers "unhandled method".
- Tablets use `res/menu-xlarge/` and a two-pane deck list; check both layouts.
- The leaderboard talks to a third-party server as an unofficial client and identifies itself as
  the desktop add-on's version.

## Licences

AnkiDroid is GPL-3.0-or-later; Anki and the fork are AGPL-3.0-or-later. These three repositories
are the complete corresponding source for APKs built from them. The league shields and country
flags under `assets/leaderboard` come from the Anki Leaderboard add-on, whose author marks them
as free to reuse; `assets/rwkv/model.bin` is the fork's model file.
