# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**Daftar** — an Android reader for an [Obsidian](https://obsidian.md) vault kept in a Git repository. It syncs markdown straight from GitHub with no server in between and renders it the way Obsidian does. It is **read-only by design**: editing on a phone means merge conflicts, and the thing worth having on a phone is the reading.

The repository is **public**; the vault it reads is private. See [Public-repo hygiene](#public-repo-hygiene) before committing anything derived from real content.

`README.md` is the outward-facing document and explains how sync, transports and signing work. It is not duplicated here. This file is what a person working on the code needs and the README would not say.

## Commands — always `just`

```bash
just doctor    # confirm the toolchain is usable
just ci        # everything CI runs: ktlint, android lint, tests, assemble
just test      # JVM unit tests
just lint      # ktlint + android lint (warnings are errors)
just fmt       # ktlint format
just build     # debug apk
just install   # onto the connected device
just logs      # logcat, filtered
just wipe      # pm clear -- forces a fresh sync, and takes the SSH key with it
just bump X.Y.Z
just release-check
just verify-apk
```

`JAVA_HOME` and `ANDROID_HOME` are exported by the justfile, so recipes work without a configured shell. Running Gradle directly does not get that for free.

## Toolchain constraints

- **JDK 17, and nothing newer.** AGP 9.4.0 requires it and rejects later JDKs with an unhelpful error. Gradle will happily pick a newer JDK if one is on the path first.
- **AGP 9 has Kotlin built in.** Never apply `org.jetbrains.kotlin.android`, and there is no `kotlin { }` extension to configure. The convention plugins in `build-logic/` are where compiler settings live.
- `compileSdk`/`targetSdk` 37, `minSdk` 29, all declared once in `AndroidSdk` in `build-logic/`.
- Unaccepted SDK licences fail every build with a message that does not mention licences. `just sdk` accepts them.

## Modules

```text
core/markdown/   kotlin("jvm")     parser, link resolver, block model -- NO Android
core/sync/       kotlin("jvm")     transports, planner, VaultFilter  -- NO Android
core/icons/      kotlin("jvm")     Iconic plugin config parsing      -- NO Android
core/data/       android-library   Room, DataStore, files, WorkManager
app/             application       Compose UI and DI wiring
```

**Keep Android out of the three JVM modules.** Their tests run in seconds instead of forty, and the compiler physically prevents reaching for a `Context`. If a piece of logic is worth iterating on, it belongs in one of them. This is also why CI runs no emulator and there are no instrumentation tests: the risk surface that can be tested on the JVM is most of it.

## The rule that keeps being broken: everything is scoped to a vault

The app reads several repositories, and they are **separate vaults** — their own files, index, search and tasks. A `[[wikilink]]` resolves inside the vault that wrote it and nowhere else. Paths are relative to the vault holding them, so two vaults can each hold a `README.md`, an `uploads/logo.png`, a `LICENSE`.

**Every query, every key, every file path takes a `vaultId`.** Four separate bugs have come from one place missing it:

- `IndexDao.idOf` looked up a note by path alone, so indexing the second vault overwrote the first vault's note at the same path.
- `blobs` was keyed by `path` alone. The migration that made paths relative collided two rows onto one key, could not commit, and **left the database unopenable — the app would not start at all**, on every launch.
- The quick switcher (`searchByName`) had no `vaultId` filter, so typing a name offered notes from every repository while everything around it showed one.
- `attachmentPaths` selected "everything that is not markdown", which swept in the synced `CONFIG` blob and put a `.obsidian` folder at the top of the tree.

When adding a query, the question is not "does this work" but "which vault is this about". When reviewing one, check the `WHERE` clause first.

## Room

- **`BundledSQLiteDriver`**, so FTS5 ships with the app rather than depending on the device's SQLite. It also means the database tests are plain JVM tests.
- **FTS5 is created by hand** in a `RoomDatabase.Callback`. Room only annotates FTS3/FTS4 and neither can rank. `note_fts` is not an `@Entity`, is not in the exported schema, and must be excluded from migration assertions.
- **`@Transaction`, never `androidx.room.withTransaction`** — the latter throws under the driver API.
- Migrations are hand-written and registered in **two** places: `DataModule` and `MigrationTest`. Miss the first and installed devices cannot move forward.

### Migration testing — read this before writing one

`MigrationTest` builds each old version from its exported schema JSON, runs the migrations, and asserts the resulting shape. **Until recently every one of those ran against empty tables**, and a shape assertion on an empty database cannot fail the way a real one does: `UNIQUE constraint failed` needs two rows to collide. That is exactly how a migration that bricked the app shipped through a green suite.

So: **a migration that touches data gets a test with data in it.** `two vaults keep a file they both name the same` is the pattern to copy. The other cases still run dry and are worth seeding when you next touch them.

`CURRENT` is derived from the registered migrations, not written down — a stale literal silently checks every migration against an older schema. `the schema and the migrations agree on the version` catches a `@Database` bump that arrived without a migration.

## R8 strips what it cannot see

Anything resolved reflectively or by name string gets renamed or removed in release builds, and **only release builds**. `net.i2p.crypto.eddsa` was stripped once and SSH failed with "no keys to try" on a signed APK while debug worked perfectly. The keep rules in `app/proguard-rules.pro` cover jlatexmath, JGit, Apache sshd and eddsa.

`tools/verify-apk.sh` checks the built APK still contains what it needs. `just verify-apk` runs it, and CI runs it before publishing.

## Releasing

```bash
just bump 0.13.7
$EDITOR fastlane/metadata/android/en-US/changelogs/1307.txt
git commit -am "chore: release 0.13.7"
git tag -a v0.13.7 -m "v0.13.7" && git push --follow-tags
```

**The tag must be annotated.** `git push --follow-tags` ignores a lightweight one, so the commit lands, the workflow never fires, and the release goes quietly missing. This has happened.

`versionCode` is derived from the version (`0.13.7` → `1307`) so it cannot go backwards, and the workflow refuses a tag that disagrees with `app/build.gradle.kts`. CI signs; nothing is published by hand. Every release carries its certificate fingerprint — an APK signed with a different key cannot update an installed one, and uninstalling takes the synced vault, the token and the on-device SSH key.

## Verifying work, because compilation does not

Several features in this project were written, compiled, reviewed and shipped **without ever being called**. `schedulePeriodic()` had no callers. `onAttachment` defaulted to an empty lambda. `notes.isRtl` was carried all the way to the UI and never read. The SSH key list was a one-shot snapshot that nothing re-took. A compiler cannot report an absent call site.

Three habits that catch this:

1. **When editing by script, assert the anchor matched.** A `str.replace` that silently finds nothing leaves a file that compiles because nothing references the code that never arrived.
2. **When adding a test, confirm it fails without the fix.** Revert the change, run the test, see it red, restore. A test that passes either way is worse than none, because it is counted.
3. **When wiring something new, find the call site and read it.** "It compiles" means the types line up, not that it runs.

## Testing on the device

The app is sideloaded onto one phone. With it connected over USB, `adb` is the fastest loop there is and beats asking:

```bash
adb logcat -b crash -d                 # the last crashes, with stack traces
adb install -r daftar-X.Y.Z.apk        # keeps app data; needs the same signing key
adb shell am start -n me.parham1995.notes/.MainActivity
adb exec-out screencap -p > shot.png   # then read the image
adb shell input tap X Y                # drive the UI
```

Coordinates from a screenshot need scaling to the device's real resolution, and they go stale the moment anything scrolls — take the screenshot and tap from it in the same breath. `adb shell input text` drops characters on long strings, so type short runs. A release build is not debuggable, so `run-as` cannot reach app storage; logcat and screenshots are what there is.

**What cannot be driven from here at all:** multi-touch and stylus hover. `adb shell input` is single-pointer, and `sendevent` to the touchscreen is refused by SELinux (`Permission denied`) on a stock Samsung. Pinch-to-zoom and anything the S Pen does have to be tried by hand — do not claim either works because it compiled.

The app also records its last crash itself, readable at **Settings → Advanced** with a Share button, because a sideloaded app has no store console behind it. That is the right thing to ask for when the device is not to hand — but if the app will not start, the card cannot be reached and only logcat will do.

## Synced, but not shown

A `CLAUDE.md` is instructions for the tooling that writes the vault, not something anyone reads on a phone — and there is one in nearly every folder worth browsing, so as notes they sat at every level of the tree and answered to any search for a word about conventions. Nothing in the vault links to them.

They are **still synced and still on disk**: the exclusion is at index time, in `VaultIndexer.GUIDES`. That is the boundary between "synced" and "shown", and skipping a file there keeps it out of the tree, the search, the tasks and the graph at once — rather than a filter each of those has to remember to apply, which is how the `.obsidian` folder and the cross-vault search both happened.

Do not solve this in `VaultFilter` instead. That decides what reaches the device, and narrowing it has never been exercised — both existing `VERSION` bumps widened it, so whether a re-plan removes rows that stopped being eligible is unverified.

## Public-repo hygiene

- **All test fixtures are synthetic.** Real vault paths leak plenty on their own — folder names alone disclose employers, relationships and where someone lives.
- **No tokens, ever.** The access token is runtime-only: never a `BuildConfig` field, never in `local.properties`, never in CI.
- Screenshots in `fastlane/metadata/` are outward-facing. Treat them the way you would treat a screen shared in a talk.

## Assorted traps, each paid for once

- **A deploy key belongs to exactly one repository.** GitHub returns 422 "key is already in use" on the second. Hence one key per vault, generated on the device.
- **A global gitignore can hide a whole directory.** A bare `Icon` pattern matched `ui/icon/` on a case-insensitive filesystem, so an entire package was never committed and CI had never run its tests. `!icon/` in `.gitignore` is the fix, and the symptom is a tree that builds locally and not in CI.
- **Compose `Text(style = …)` replaces `LocalTextStyle`, it does not merge with it.** Hence the `inScript()` helper.
- **A `Scaffold` lays its content slot out as a box, not a column.** Two things emitted side by side there are drawn on top of each other: a tab strip and a full-height column came out with the strip behind the text and the column swallowing every tap meant for it. Put them in a `Column` and apply the bar's inset once.
- **An id is not an index.** `ParsedHeading` recorded its block's id and the outline scrolled to it as though it were a position. Ids are handed out to nested blocks too — every list item, every line in a callout — so the number runs ahead the moment a note holds a list, and the scroll clamps to the end and appears to do nothing. The outline was inert from the day it was written.
- **A `DisposableEffect` keyed on the thing it cleans up disposes the new value, not the old one.** Its cleanup runs after the key changed and reads current state. This closed a PDF document at the moment it opened, and the viewer drew nothing for weeks. Where a producer owns a resource, close it with `produceState`'s `awaitDispose`.
- **`PdfRenderer` allows one page open at a time and is not thread safe**, which a lazy list will absolutely violate.
- **XML:** `--` is illegal inside a comment, `tools:ignore` needs its namespace, and `previewLayout`/`targetCell*` are API 31+ so they live in `res/xml-v31/`.
- **New user-facing strings need a `values-fa` translation**, or lint fails the build.
- **ktlint's `no-consecutive-comments`:** inserting a function immediately before another orphans that one's KDoc. Anchor insertions on the `/**`, not on the `@Composable`.
