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

## Writing: three rules, and none of them is optional

The app writes, but only three edits: tick a task, add a task, append to a scratchpad. `VaultWriteRepository` is the only place any of it happens. If something new wants to write, it goes through there.

- **Ask the host what the credential may do; never infer it.** `permissions.push` on REST, an abandoned `Transport.openPush()` on SSH. A read-only deploy key and a `Contents: read-only` token are indistinguishable from working ones until the push, so guessing permissively means the person finds out *after* typing the thing they wanted to keep. The answer lives on `vaults.canWrite`, refreshed every sync, and every affordance is gated on it plus an author being set.
- **An edit is a function of the file's current text, never a patch.** `VaultEdits` holds all three as pure `(String?) -> String?`, and every conflict — GitHub's stale-sha refusal, a rejected push — is answered by reading again and calling the function again. Returning null means "the file already says this", which is a quiet success, not a failure.
- **Queue first, flush at the start of a sync.** An edit is applied locally and written to `pending_edits` before it is attempted, so it survives no signal and a crash. Flushing *after* a pull would let an SSH vault's `reset --hard` wipe the local copy of something still queued, which looks exactly like losing it.

A task is found again by the source line the indexer recorded (`tasks.line`, from commonmark's block source spans), then checked against what the index made of that line. Text alone cannot do it — the stored text has had markup and emoji metadata stripped — and a line number alone cannot either, because notes grow paragraphs above tasks.

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
- **`TopAppBar`'s `navigationIcon` slot is a box too.** Two `IconButton`s put in it directly are drawn one on top of the other; the back arrow spent a build hidden underneath the drawer's. Wrap them in a `Row`.
- **A `Scaffold` lays its content slot out as a box, not a column.** Two things emitted side by side there are drawn on top of each other: a tab strip and a full-height column came out with the strip behind the text and the column swallowing every tap meant for it. Put them in a `Column` and apply the bar's inset once.
- **An id is not an index.** `ParsedHeading` recorded its block's id and the outline scrolled to it as though it were a position. Ids are handed out to nested blocks too — every list item, every line in a callout — so the number runs ahead the moment a note holds a list, and the scroll clamps to the end and appears to do nothing. The outline was inert from the day it was written.
- **A `DisposableEffect` keyed on the thing it cleans up disposes the new value, not the old one.** Its cleanup runs after the key changed and reads current state. This closed a PDF document at the moment it opened, and the viewer drew nothing for weeks. Where a producer owns a resource, close it with `produceState`'s `awaitDispose`.
- **`PdfRenderer` allows one page open at a time and is not thread safe**, which a lazy list will absolutely violate.
- **XML:** `--` is illegal inside a comment, `tools:ignore` needs its namespace, and `previewLayout`/`targetCell*` are API 31+ so they live in `res/xml-v31/`.
- **New user-facing strings need a `values-fa` translation**, or lint fails the build. An unused one fails it too, so do not add strings ahead of the code that uses them.
- **A lambda that returns `Job` is not a `() -> Unit` when the expected type is spelled out.** `viewModelScope.launch {}` coerces fine as an `onClick` argument and stops coercing the moment it goes through `takeIf` or a typed local. Declare the local as `(() -> Unit)?` and put the call in a block.
- **Parsing one line on its own is not parsing it in context.** An indented `    - [ ] child` is a list item inside a note and a paragraph on its own, because indented code blocks are disabled and four spaces are no longer a marker position. `TaskLine.indexedText` trims before parsing for exactly this reason.
- **The journal goes to logcat too, under the tag `daftar`.** `adb logcat -s daftar` is the whole thing, live. The on-device journal is still the copy that matters, but reading it means navigating four screens by hand -- an hour went into a write that silently did nothing with the answer sitting in a database table.
- **A sync and a write must not overlap.** For an SSH vault they drive the same directory -- one checking a tree out, the other committing from it -- and WorkManager fires whenever it likes. `VaultGate` is the single lock; it is **not reentrant**, so anything called from inside it takes the unlocked form (`VaultWriteRepository.drain`, `SyncRepository.syncLocked`).
- **Room's `@Upsert` returns -1 when it resolved to an update, not the row's id.** Anything keyed on that id then writes against `-1` -- rows no query will ever find -- while the row itself updates perfectly. In `IndexDao.writeBatch` this meant a note that already existed kept the headings, links and tasks it had when it was *first* indexed, however often it changed afterwards. It hid for so long because a full `indexAll` runs whenever `VaultIndexer.VERSION` moves, which is most releases, and that path inserts rather than updates. Take the id from the lookup, not from the upsert.
- **For an SSH vault the working tree *is* the file store.** An edit is written there optimistically before it is sent, and `reset --hard` does not remove an untracked file -- so reading the file back to compute the edit reads the app's own scribble. Read from the commit (`TreeWalk.forPath` on the resolved head), never from disk.
- **A `BackHandler` composed inside a `ModalNavigationDrawer`'s content wins over the drawer's own.** The drawer registers one when it opens, but anything deeper in the composition takes priority, so a screen handler will navigate the page underneath instead of shutting the drawer covering it. Guard it with `!drawerState.isOpen`.
- **Do not enable a drawer's swipe-to-open.** The left edge is the system back gesture. `gesturesEnabled = drawerState.isOpen` keeps swipe-to-close, which is the half worth having.
- **ktlint's `no-consecutive-comments`:** inserting a function immediately before another orphans that one's KDoc. Anchor insertions on the `/**`, not on the `@Composable`.
