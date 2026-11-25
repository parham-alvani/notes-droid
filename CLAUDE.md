# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**Daftar** — an Android reader for an [Obsidian](https://obsidian.md) vault kept in a Git repository. It syncs markdown straight from GitHub with no server in between and renders it the way Obsidian does. It is **a reader first**: the thing worth having on a phone is the reading. What it writes is small and guarded against merge conflicts: named edits, and a small editor of a note's text that refuses to save over a file that changed.

The repository is **public**; the vault it reads is private. See [Public-repo hygiene](#public-repo-hygiene) before committing anything derived from real content.

`README.md` is the outward-facing document and explains how sync, transports and signing work. It is not duplicated here. This file is what a person working on the code needs and the README would not say. A lesson that holds for any desktop app (a data-safety rule, a testing or release lesson, a way to measure) also goes into the `taller` plugin for Claude Code, which gathers the three apps' lessons, with its reason and commit; this file keeps what is about this app.

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
core/obsidian/   kotlin("jvm")     bookmarks, daily notes, obsidian:// -- NO Android
core/dictionary/ kotlin("jvm")     bundled WordNet: lookup, inflections -- NO Android
core/data/       android-library   Room, DataStore, files, WorkManager
app/             application       Compose UI and DI wiring
```

**Keep Android out of the five JVM modules.** Their tests run in seconds instead of forty, and the compiler physically prevents reaching for a `Context`. If a piece of logic is worth iterating on, it belongs in one of them. This is also why CI runs no emulator and there are no instrumentation tests: the risk surface that can be tested on the JVM is most of it.

## The rule that keeps being broken: everything is scoped to a vault

The app reads several repositories, and they are **separate vaults** — their own files, index, search and tasks. A `[[wikilink]]` resolves inside the vault that wrote it and nowhere else. Paths are relative to the vault holding them, so two vaults can each hold a `README.md`, an `uploads/logo.png`, a `LICENSE`.

**Every query, every key, every file path takes a `vaultId`.** Four separate bugs have come from one place missing it:

- `IndexDao.idOf` looked up a note by path alone, so indexing the second vault overwrote the first vault's note at the same path.
- `blobs` was keyed by `path` alone. The migration that made paths relative collided two rows onto one key, could not commit, and **left the database unopenable — the app would not start at all**, on every launch.
- The quick switcher (`searchByName`) had no `vaultId` filter, so typing a name offered notes from every repository while everything around it showed one.
- `attachmentPaths` selected "everything that is not markdown", which swept in the synced `CONFIG` blob and put a `.obsidian` folder at the top of the tree.

When adding a query, the question is not "does this work" but "which vault is this about". When reviewing one, check the `WHERE` clause first.

## Writing: three rules, and none of them is optional

The app writes fourteen edits. One is the editor's save of a note's whole text (`EditKind.EDIT`, see [The editor](#the-editor)), which writes only while the file is still the text the editor opened. The other thirteen are named edits: tick a task, add a task, move a task to another day, set a task's status (the board's columns and card menu, `EditKind.SET_STATUS`, with the reason a cancel asks for), set a task's reason alone (`EditKind.SET_REASON`), append to a scratchpad, write a generated note whole (the calendar's week notes), make a new note from a template or from a link to nothing (`EditKind.CREATE_FILE`, which refuses rather than writes over a note that is already there), sort a note's tasks done first (`EditKind.SORT_TASKS`, a 1:1 port of the vault's `task-sorter` plugin in `TaskSorter`, which the Mac has too -- change one and the other has to follow), change one property of a note's front matter from the Properties form (`EditKind.SET_PROPERTY`: `PropertySheet` in core/markdown, `properties.md` in notes-linux's `docs/specs/`; the anchor is the front matter the form was read from, and the edit is refused when the note's front matter is no longer that), restore a note as it was at a commit of its history (`EditKind.RESTORE`, an ordinary write, so the commit it makes keeps what it replaced), mark a concept as verified (`EditKind.MARK_VERIFIED`, see [The Open Knowledge Format](#the-open-knowledge-format)), and set one of the app's settings in the vault's `.daftar.toml` (`EditKind.SET_SETTING`, see [Settings from the vault](#settings-from-the-vault)). A note's history is `NoteHistory` (core/sync): GitHub's commits of a path for a REST vault, following a rename by asking the oldest commit listed what it renamed (`RestNoteHistory`), and JGit's `FollowFilter` on the clone for an SSH vault (`GitNoteHistory`), which is one commit deep, so its history starts at the clone. `VaultWriteRepository` is the only place any of it happens. If something new wants to write, it goes through there.

A generated note (`EditKind.REPLACE_FILE`, `writeGenerated`) is the one edit that does not keep what the file said, so it is only for a note the app owns outright: the week notes in `Calendar/` hold nothing a person wrote. The export writes only `Calendar/<year>-W<week>.md`; the folder's `CLAUDE.md` in the vault is the one file there a person writes, and the app never touches it. Its text must come out the same for the same input -- no "generated at" line -- because an unchanged note is how a sync with no calendar changes makes no commit. A newer copy drops the older one still in the queue. The week notes land in a vault whose CI runs markdownlint and `prettier --check`, so `CalendarWeeks.clean` escapes markdown in calendar text and makes every address and link an autolink (bare ones fail MD034), and a sub-item is indented four spaces (MD007). After changing the format, render a week of awkward input and run the vault's own `npx markdownlint-cli2` and `npx prettier --check` on it.

- **Ask the host what the credential may do; never infer it.** `permissions.push` on REST, an abandoned `Transport.openPush()` on SSH. A read-only deploy key and a `Contents: read-only` token are indistinguishable from working ones until the push, so guessing permissively means the person finds out *after* typing the thing they wanted to keep. The answer lives on `vaults.canWrite`, refreshed every sync, and every affordance is gated on it plus an author being set.
- **An edit is a function of the file's current text, never a patch.** `VaultEdits` holds every one as a pure `(String?) -> String?`, and every conflict — GitHub's stale-sha refusal, a rejected push — is answered by reading again and calling the function again. Returning null means "the file already says this", which is a quiet success, not a failure — so an edit asked about a task that is no longer there must not return null. `VaultEdits.reschedule` and `VaultEdits.setStatus` throw `TaskNotFound` instead, and the write path reports it.
- **Queue first, flush at the start of a sync.** An edit is applied locally and written to `pending_edits` before it is attempted, so it survives no signal and a crash. Flushing *after* a pull would let an SSH vault's `reset --hard` wipe the local copy of something still queued, which looks exactly like losing it.

A task's statuses are the vault's, not the app's: `TaskStatuses` (core/markdown) is built from the Tasks plugin's `data.json` by `TasksPlugin` (core/obsidian), and every rule that asks "is this open, done, cancelled" goes by a status's *type*, never its character -- `[r]` "In Review" is under way exactly as `[/]` is. The index stores the character (`tasks.status`) and derives `state` and `open` from it with the vault's statuses; a new `data.json` arrives with a sync without any note changing, so `VaultIndexer.restate` puts every task right after each sync. A task's `[reason:: …]` (`TaskReason`) is taken out of its text everywhere the text is shown or matched, belongs to the status it was given with, and comes off with any status change. Daftar for Linux and the Mac follow the same spec, `task-reasons-and-statuses.md` in [`docs/specs/`](https://github.com/parham-alvani/notes-linux/tree/main/docs/specs) in `notes-linux`; change a rule here and theirs have to follow. A new rule the three apps share is written there first, with the test cases every app must have, and gets one branch and one pull request per repository, named the same.

A task is found again by the source line the indexer recorded (`tasks.line`, from commonmark's block source spans), then checked against what the index made of that line — `TaskLine.locate`, shared by the tick and the move. Text alone cannot do it — the stored text has had markup and emoji metadata stripped — and a line number alone cannot either, because notes grow paragraphs above tasks.

## Room

- **`BundledSQLiteDriver`**, so FTS5 ships with the app rather than depending on the device's SQLite. It also means the database tests are plain JVM tests.
- **FTS5 is created by hand** in a `RoomDatabase.Callback`. Room only annotates FTS3/FTS4 and neither can rank. `note_fts` is not an `@Entity`, is not in the exported schema, and must be excluded from migration assertions.
- **`@Transaction`, never `androidx.room.withTransaction`** — the latter throws under the driver API.
- Migrations are hand-written and registered **once**, in `NotesDatabase.MIGRATIONS`. `DataModule` and `MigrationTest` both read that list; they used to keep a copy each, and a migration in the test's copy alone left installed devices with no way forward.

### Migration testing — read this before writing one

`MigrationTest` builds each old version from its exported schema JSON, runs the migrations, and asserts the resulting shape. **Until recently every one of those ran against empty tables**, and a shape assertion on an empty database cannot fail the way a real one does: `UNIQUE constraint failed` needs two rows to collide. That is exactly how a migration that bricked the app shipped through a green suite.

So: **a migration that touches data gets a test with data in it.** `two vaults keep a file they both name the same` is the pattern to copy, and `withData` in `MigrationTest` is the short form: build one version, seed it, run one migration, check. 3→4, 10→11 and 11→12 are seeded; the rest still run dry and are worth seeding when you next touch them.

`CURRENT` is derived from the registered migrations, not written down — a stale literal silently checks every migration against an older schema. `the schema and the migrations agree on the version` catches a `@Database` bump that arrived without a migration.

## R8 strips what it cannot see

Anything resolved reflectively or by name string gets renamed or removed in release builds, and **only release builds**. `net.i2p.crypto.eddsa` was stripped once and SSH failed with "no keys to try" on a signed APK while debug worked perfectly. The keep rules in `app/proguard-rules.pro` cover jlatexmath, JGit, Apache sshd and eddsa.

`tools/verify-apk.sh` checks the built APK still contains what it needs, including every class JGit and sshd name in `META-INF/services`. `just verify-apk` runs it. CI builds a debug-key-signed release APK on every push and runs it there too, so R8 trouble shows up before a tag rather than after one.

## Driving the app over adb

`tools/daftar` (or `just daftar …`) talks to `AdbSetupReceiver` through `am broadcast`: `status`, `add-vault OWNER REPO [NAME] [BRANCH]`, `remove-vault`, `transport VAULT ssh|rest`, `key VAULT` (prints the public line, generating it first), `test-key`, `token` (from stdin), `author NAME EMAIL`, `sync`, `digest` (switches the daily digest on and posts it now). It is how a wiped phone is set up again in a minute, and how anything that would otherwise mean typing into a text field under a predictive keyboard is done -- `input text` goes through the keyboard, which completed "documents" three different ways. The receiver is exported but requires `android.permission.DUMP`, which only the shell and the system hold, so adb is the gate. Every argument is single-quoted for the device's second shell parse; an empty extra cannot be passed at all, so the wrapper omits it.

## Baseline profile

`app/src/main/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt` are recorded, not written: `just baseline-profile` drives the installed app on the connected phone through `baselineprofile/` (a `com.android.test` module with UI Automator) and writes what ART compiled on the way. The build type it records from, `nonMinifiedRelease`, is signed with the release key on purpose, so it installs over the phone's copy instead of asking for an uninstall. CI never records; it ships the committed file, and R8 and ART compile those paths on install. Re-record when a release changes what happens in the first seconds -- a new renderer, a new start screen -- not for every release.

**Recording uninstalls the app on the device.** The connected-test task behind it removes the app under test around the run, data and all, whatever key it is signed with -- `firstInstallTime` on the phone moved to the minute the recording ran, and the vault, the token, the on-device SSH key and every setting went with it. The recipe refuses to run without `DAFTAR_WIPE_OK=1` for that reason. Record on an emulator, or on a device whose vault you are prepared to add again; never on the phone that is the only copy of a configured vault.

## Releasing

```bash
just bump 0.13.7
$EDITOR fastlane/metadata/android/en-US/changelogs/1307.txt
git commit -am "chore: release 0.13.7"
git tag -a v0.13.7 -m "v0.13.7" && git push --follow-tags
```

**`git add` the changelog before `git commit -am`.** `-a` stages modified files, not new ones, so a fresh `NNNN.txt` is left behind and the tag's APK is built with an empty `BuildConfig.CHANGELOG` -- the What's new sheet after that update shows nothing. 0.32.0 shipped that way. `just release-check` now refuses an untracked or placeholder changelog; run it after the commit, not before.

**The tag must be annotated.** `git push --follow-tags` ignores a lightweight one, so the commit lands, the workflow never fires, and the release goes quietly missing. This has happened.

`versionCode` is derived from the version (`0.13.7` → `1307`) so it cannot go backwards, and the workflow refuses a tag that disagrees with `app/build.gradle.kts`. CI signs; nothing is published by hand. Every release carries its certificate fingerprint — an APK signed with a different key cannot update an installed one, and uninstalling takes the synced vault, the token and the on-device SSH key.

## Compose is testable here, on the JVM

`app` runs Compose tests under Robolectric -- no emulator, and none wanted. Two
things make it work, and both are easy to miss:

- **`robolectric.properties` must pin `sdk=34` per module.** Robolectric needs
  Java 21 to sandbox SDK 37 and AGP 9 pins the build to Java 17. The emulated
  runtime does not have to match `compileSdk`.
- **`isIncludeAndroidResources = true`** in `testOptions.unitTests`, or the
  composition cannot resolve a theme.

Use `androidx.compose.ui.test.junit4.v2.createComposeRule`; the v1 one is
deprecated and `-Werror` fails the build on it.

What to spend the tests on is the layer where this app's bugs actually shipped:
**things that compiled, rendered, and did nothing.** A tap that reaches no
handler, a setting wired to nothing, two buttons drawn on top of each other.
`TaskRenderingTest` asserts a checkbox tap hands back the line it was written
on; `TopBarLayoutTest` asserts two navigation buttons have disjoint bounds, and
carries a second test proving the framework overlaps them without the `Row`, so
the first test cannot quietly stop meaning anything.

### Screenshots

Semantics cannot see a block painted over another, a heading's rule in the wrong colour, or a Persian paragraph set left-to-right. `RendererScreenshotTest` draws synthetic notes through the real `MarkdownDocument`, in `NotesTheme` with `LocalReading`, and compares the pixels with the goldens in `app/src/test/screenshots/` (Roborazzi, on the same Robolectric setup). `just test` and CI run the comparison through `verifyRoborazziDebug`; a failure leaves the diff images under `app/build/outputs/roborazzi/`. After a change that is meant to move pixels, `just screenshots-record` redraws the goldens -- look at them before committing, because the record step accepts whatever was drawn.

Three things the pictures depend on:

- **`@GraphicsMode(NATIVE)` on the class.** Roborazzi draws through Robolectric's native graphics, and the default mode's made-up text metrics are the ones `TextScaleTest` already had to leave. Roborazzi's docs also suggest `robolectric.pixelCopyRenderMode=hardware`; the captures here came out identical with and without it, so it is not set.
- **The icon set and the code colours arrive after the first frame.** `ProvideLucide` reads the icons off the main thread, so the test loads them itself and provides `LocalLucide`; code highlighting runs on `Dispatchers.Default`, so the test waits for the span styles. A picture taken without either is of a page with no checkboxes and grey code, and it will be flaky.
- **The `highlights` library is Java 21 bytecode and the tests run on 17.** `CodeHighlighter` keeps every reference to it out of class initialisation so a code block can be composed at all on the JVM; the fixture uses `yaml`, which one of the app's own grammars colours. A fence in another language falls back to plain text in a test and is coloured on the phone.

The goldens are recorded on macOS and checked on Linux. The comparison lets a pixel's colour drift a little (`MAX_PIXEL_DISTANCE`, for anti-aliasing on another OS) and lets no pixel change outright -- a percentage-of-pixels threshold was tried first and waved through a chevron 40% larger, because a chevron is a few hundred pixels of a quarter of a million. Whether Linux lands inside the colour tolerance has not been proven here; if CI fails on a golden that passes locally with no change behind it, the fix is to record on CI's platform or widen `MAX_PIXEL_DISTANCE`, not to delete the test.

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

Some files are not for a person to browse: what the vault's own Obsidian "Excluded files" (`userIgnoreFilters` in `.obsidian/app.json`) leaves out -- an `uploads/` of attachments, usually -- and the instructions AI agents read, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `CLAUDE.local.md`, of which a vault written with their help has one in nearly every folder. Listed, they sat at every level of the tree and answered to any search for a word about conventions.

They are **still synced, still indexed and still on disk**. A link or an embed into one resolves, and it opens like any note; only the lists leave it out. `HiddenFiles` (core/obsidian) is the rule, `HiddenFileStore` puts it together from `app.json` and the two Reading settings ("Hide files for AI agents", on; "Show hidden files", off), and `notes.hidden` is the rule written down per note: the indexer sets it on every note it writes, and `HiddenFileStore.apply` puts it right after every sync (a new `app.json`) and whenever one of the settings moves (`NotesApplication.keepHiddenFilesCurrent`, which also runs on launch). Attachments carry no mark, so `VaultRepository.buildChildren` asks the same rules for them.

The guides used to be left out at index time instead (`VaultIndexer.GUIDES`), which kept them out of every list at once but made every link to one broken, and it cannot be done to `uploads/` at all -- the notes embed from it. The price of the mark is that **every query that lists notes has to say `hidden = 0`**: the tree, the count, recents, random, search, the switcher, tags, tasks and their counts, backlinks, the graph. A query that resolves a link or opens a note by id (`allIds`, `byPath`, `byId`, `tasks.byNote`) must not, and bases see hidden notes too, because a base lists what its author asked for. `HiddenFileStoreTest` asks each list; a new list belongs there.

Do not solve this in `VaultFilter` instead. That decides what reaches the device, and narrowing it has never been exercised -- every `VERSION` bump widened it, so whether a re-plan removes rows that stopped being eligible is unverified. It syncs `app.json` for this, and nothing else from it is read.

## Notes that open folded

A vault says its notes open with every section shut, each heading saying what is under it, with `folded = true` under `[reader]` in the `.daftar.toml` at its root. The rules are `folded-sections.md` in notes-linux's `docs/specs/`, which Daftar for Linux and the Mac follow too; change one here and theirs have to follow.

- **`.daftar.toml` is the one dotfile that syncs.** It is in `VaultFilter.DEFAULT_CONFIG_PATHS` by its exact name (`VERSION` 11), a `CONFIG` blob like the `.obsidian` files, so it is fetched with the markdown and no list offers it. `DaftarConfig` (core/obsidian) reads it line by line for that one key and cannot throw; it is not a TOML parser, and `daftar-cli`'s own sections in the file go unread. `ObsidianConfigStore.daftar` reads it from disk when asked.
- **The app's word is `FoldSections`** (Settings → Reading): as the vault says, always, never. `FoldSections.folds(vault)` is the whole decision.
- **No list kept is not an empty list kept.** `notes.folds` holds block positions; the empty string is a note the person never folded or opened, which opens as the settings say, and `-` (`Folds.NONE_SHUT`) is a note they opened whole, which stays open. `-` is written only while notes open folded. No migration: the empty string is what every note already had. `HeadingFolds.opening` turns the kept list and the settings into the folds a note opens with.
- **`NoteViewModel.setFolds` is the only place a list is written**, and `reveal` goes through it, so a note that lands inside a shut section (where it was being read, a link to a heading, a find hit) opens the sections that hold the place and keeps the list. `foldsKept` in the state is what lets a note with no list follow the settings when a sync brings another `.daftar.toml` or the choice changes in Settings.
- **The other apps keep `level:text` keys; this one keeps positions**, as it did before, and drops them when the file changes underneath (`IndexDao.foldsAfter`). So here a note that changed upstream has no list again and opens as the settings say.
- **The hint is `FoldHint` (core/markdown)**: `parts` cuts the beginning and decides which counts are said, `say()` is the spec's English, which the spec's table is tested against, and `of` counts a section from the parsed blocks. `FoldHintLine` in `Blocks.kt` draws it on a line of its own under the heading's text, not on the heading's line as the desktop apps do: a phone's line holds a heading and little else. The counts are said through plurals, so a Persian phone reads them in Persian. It is its own semantics node after the heading, described with commas for TalkBack, and outside the selection; `MdBlock.Heading.text`, which links, the outline and the printed note go by, never holds it.
- **The words are counted from the blocks, not from a drawn page**, so a section holding an embed, a query or a diagram can count a few words fewer than the Mac does for the same note.

## A read-only vault

A clone that pulls and never writes. The rules are `read-only-vault.md` in notes-linux's `docs/specs/`, which Daftar for Linux and the Mac follow too; change one here and theirs have to follow.

- **The clone's own git config is the one copy.** `daftar.readonly` and `remote.<name>.url` are read and written by `CloneConfig` (core/data, JGit's `StoredConfig`), and nothing of them is in the database. `CloneConfig.isReadOnly` loads `.git/config` with no parent, because `repository.config` answers from the user's config too; a value that is no git boolean is read-only. `VaultClones` is the store: `isReadOnly` asks the clone each time, `clones` reads the configs again whenever a vault's row changes (every sync does that), and `setReadOnly` and `setAddress` take the `VaultGate`, since a sync asks the clone which it is more than once.
- **Only an SSH vault is a clone.** A REST vault has no repository, so it is never read-only and Settings offers it nothing. That is the spec's "a vault that is not a git repository".
- **One refusal, in `VaultWriteRepository.apply`**, before the file is read, plus `writableVault` in front of every entry point and a third in `GitSshVaultSync.write`. `WriteResult.Refused(readOnly = true)` is the spec's `readOnly`. A new write goes through `apply` and gets the refusal for free; one that does not has a bug. The drain skips a read-only vault's queued edits without counting an attempt.
- **The pull is in `plan()`, not `apply()`.** `GitSshVaultSync.pull` fast-forwards with `DirCacheCheckout` right after the fetch, because a plan with no vault file in it is never applied and the clone would fall behind the tip it was told about. `apply()` then only records. Nothing is reset, so a hand change survives, and one the pull would write over is `ReadOnlyDiverged`.
- **A shallow clone cannot show a fast-forward.** A fetch one commit deep brings the tip without the commits between, so `isMergedInto` says no. A shallow clone still on the tip it last pulled is taken as a fast-forward; `ReadOnlyCloneTest` has the case, and removing the rule fails it.
- **`refreshWritability` skips a read-only clone.** No push is opened, not even to ask, and `vaults.canWrite` keeps the last answer for the day the switch goes off.
- **The address and port 443.** Once there is a clone, `GitSshVaultSync` uses its `remote.<name>.url`, not the URL built from owner and repo. `VaultTransports.followPortSwitch` rewrites the address when it is this repository's other GitHub form, so "Connect over port 443" still reaches the fetch; an address a person set is left alone. `PinnedHostKeys` knows GitHub only, so an address on another host fails at the host key.
- **Not here:** `core.sshCommand` and the spec's ssh command rule (the app connects with Apache sshd and its own key), `daftar-cli` and the MCP server. `DaftarConfig.agentsRefused` is what a feature that sends a note to a model must ask; there is none.

## Settings from the vault

The settings are kept in the repository: the vault's `.daftar.toml` is read over the app's own settings, and a setting changed in the app is written to it. `vault-config.md` in notes-linux's `docs/specs/`, which Daftar for Linux and the Mac follow too.

- **`SettingsStore.settings` is resolved; `SettingsStore.own` is what the phone keeps.** Everything that reads settings reads the first and follows the vault without knowing it. A setter of `SettingsStore` writes the phone and nothing else.
- **A change goes through `SettingsWriter.set(binding, value)`**, never a setter, for any setting a vault may hold: the Settings screens, the pinch in a note, the SSH screen's port, the calendar's hours. It asks `SettingsFromVault.home` where the change goes and writes the vault with `VaultWriteRepository.setSetting` (`EditKind.SET_SETTING`, one key's line through `DaftarToml.set`), or the phone with the binding's `keep`. A new call to a setter for such a setting is a change that never reaches the other devices, and nothing reports it.
- **A `SettingBinding` (in `VaultOverlay`) is one setting both ways**: `read` turns the vault's value into the app's, `said` says the app's value as a key and a TOML value. A setting whose unit differs is said in the spec's key when it is exact and in `[android]` when not: a scale of 1.15 is `android.appearance.text_scale = 115`, and once that key is in the file a later change goes there too, since the platform's table is read over the shared section. A new setting gets a binding, a rule in `SettingsFromVault.ANDROID_RULES` when it is the app's own, a row in the README's table and a label in `vaultKeyLabel`.
- **`SettingsFromVault` (core/obsidian) is the pure rule**: the allow-list, the types and ranges, the path rule, `false` only for `remote_images`, what is refused, the `[android]` table over the shared sections, and `home`, which says where a change is written. The ranges are the same numbers as the Mac's `ResolvedSettings.allowed`. Only a key of the app's own sections that is not on the list is refused; another platform's table is neither used nor named. `DaftarToml` is the reader and the one-line writer, not a TOML parser.
- **A value still moving is not written.** `SettingsWriter.setWhenStill` is for a pinch and for a row of choices a person taps along (text size, line spacing, line width, widget background): `SettingsStore.show` puts the value in memory, where `settings` shows it at once, and the write happens once, two seconds after the last step. A commit per pinch step would fill the vault's history. A new control of that kind uses it; `set` is for a switch.
- **The phone never touches the shared `git.sync_interval`.** On a desktop it is the timer of commit, pull and push, kept at 0. The phone's background refresh is `android.git.sync_interval`, in hours, and `SettingsFromVault` keeps the two names apart though they end the same.
- **A list a person wrote is not replaced by a switch.** `files.hidden` in the shared section, when it is neither the app's four names nor empty, stays; the switch writes `android.files.hidden`.
- **Which vault.** `SettingsWriter.vaultId`: the vault being read (`activeVaultId`), else the first. The same one the settings are read from. The author is the exception on the reading side: a commit takes the `git.name` and `git.email` of the vault it is made in (`VaultWriteRepository.authorFor`), after the app's own.
- **`.daftar.toml` is not a note.** `VaultWriteRepository.store` keeps a file that is not `.md` as a `CONFIG` blob and does not index it.
- **Declined, held, locked.** `FromVault.declined` is Keep on This Device, stored. `held` is a change that went to the phone for a key the vault sets (a value a vault may not hold): it holds until `vaultConfigChanged()`, which every sync calls, and is never stored. `locks(key)` is a control that is off: the vault sets the key and cannot be written. `SyncViewModel` fills `writable`; a screen that shows settings without it sees every vault-set control locked.
- **Not in the vault, on purpose**: tokens and keys, the app lock, the repositories and transports, vault ids (they are this phone's row ids), the phone's calendar ids, the author and the verifier, a gallery picture, and the folded rail, which is a view's state and would make a commit of every fold.
- **The file is read again** when the settings change and when `SettingsStore.vaultConfigChanged()` says so. `VaultConfigStore` keeps one text per vault by the file's time and size, and drops it then.

## The Open Knowledge Format

A note whose front matter has a `type` is a concept: `open-knowledge-format.md` in notes-linux's `docs/specs/`, sections 1 to 3. Sections 4 and 5 are `daftar-cli`'s.

- **`Okf` (core/markdown) reads and writes it.** `conceptOf` reads the front matter with `Yaml`, plus a flow mapping on one line (`{ by: …, at: … }`), which `Yaml` leaves as text. Nothing is refused: a malformed `stale_after` is stale, an unknown status is kept and is not stable.
- **Mark as Verified does not go through `PropertySheet`**, as the spec says it does: the sheet has no nested values here, and a `verified` list makes the form show the source. `Okf.markVerified` rewrites the lines of the `verified` key alone, as a block list with one `{ by, at }` a line, and leaves every other line as it was. The actor is the app's own setting, else the local part of the app's own author email; nothing in the vault names it. It is `EditKind.MARK_VERIFIED`, a function of the current text like every edit.
- **A Markdown link to a note is a row in `links`** (it was parsed and dropped before), so backlinks and the graph count it. `MarkdownLinks.internal` keeps a leading `/`, which is what tells `LinkResolver.resolve` to start from the bundle's root; `OkfBundles.roots` finds the roots by reading each `index.md`. A `/`, `./` or `../` path is only what it says: the file there, the file with `.md`, or a folder's `index.md`, and a `/` path never climbs above its root. A plain name or path that is no file beside the note is found as a wikilink is, as before.
- **`notes.deprecated`** is written by the indexer with the note (schema 20, `VaultIndexer.VERSION` 16). Quick open sorts by it within a score; full-text search only marks it, since two notes do not share a rank there.
- **The Concept row is in the properties sheet**, which is offered where the vault can be written and in a read-only vault. A vault whose key cannot write and is not marked read-only has no sheet, so no row; the reader's line is shown everywhere.

## The editor

A note's text is edited in `feature/editor`, opened by Edit in the note's menu. The rules are `look.md` in notes-linux's `docs/specs/` ("Live preview", "Android: the editor"), which Daftar for Linux and the Mac follow too.

- **The file's text is the only text.** `NoteEditor` is a `BasicTextField` over the Markdown as written. The sizes, the colours, the hidden marks, a callout's panel and a quote's bar are drawn: the text by a visual transformation (`EditorStyler`), the rest behind it in `drawBehind`. Nothing drawn is a character of the note.
- **One line is read at a time.** `EditorMarkup.line` (core/markdown) reads a line and the block it starts in (`EditorState`), and `EditorDocument` keeps the lines between key presses, so a key reads its own line again and no other unless it opened or closed a block. It is not the reader's parser: a mark has to close on the line it opened on. `EditorLine.drawn` keeps the styled text of a line, so `EditorStyler` makes only the lines that changed or that the selection touches. The text field still lays the whole note out on a key; that is Compose's, not ours.
- **Every line is a paragraph, and its line break is drawn as a zero-width space.** A line needs its own height (1.65, and 2 right to left), direction and indent, which is a `ParagraphStyle`. A text field draws the `\n` between two paragraphs as one more empty line, so the drawn text has U+200B in place of each `\n`, and one more at its end for the last line. The drawn text is one character longer than the note; `EditorOffsets` maps the extra place to the note's end. Take the swap away and every line of the note is followed by an empty one.
- **A hidden mark is `fontSize = 0.sp`**, not a removed character, so offsets stay the same and no offset mapping can go wrong. The caret is never among hidden marks: a caret that touches an element shows its marks (`EditorLine.hidden`).
- **`LineHeightStyle` must be `Trim.None`.** With the default, a paragraph of one line has both its ends trimmed and the line height does nothing.
- **The look is `EditorLook` (ui/theme)**: `EditorMetrics` holds the spec's numbers, and `ColorScheme.editorLook` takes the colours from `MarkupColors` and `AccentColors`, so Daylight, naz and naz, soft need no table of their own.
- **The save is `EditorSession`**: `VaultWriteRepository.edit` with the text that was opened as the anchor. A file that changed meanwhile is `WriteResult.Refused(changed = true)`: nothing is written, the editor stays open and offers Reload and Copy My Text. An edit that is queued and then finds the file changed at the push stays in the queue with its error, as a property change does; its row in Settings has Copy My Text (`QueuedEditRow`), since the queue then holds the only copy of what was typed. A save refused for another reason (the vault cannot be written now) sets `EditorSession.refused`: the page says why and offers Copy My Text and Close Without Saving, behind a confirm, because Done and Back would refuse for ever.
- **What was typed outlives the app.** `EditorViewModel` gives `EditorSession.draft()` (the opened text, the edited text, the selection) to the saved state when the app leaves the screen, and `restore` opens with it, so the system ending the app in the background loses nothing. The opened text is kept with it, so a file that changed while the app was away is still refused. A note over `DRAFT_LIMIT` characters, both texts together, is not kept: the saved state has well under a megabyte. No key, button or undo changes the text while a save is on its way (`saving`), since the text being written is the text the editor closes with.
- **The editor replaces the note's page** from inside `NoteScreen` (an early return), so it works in both layouts and the reading place is kept. When it closes under another heading than it opened at, the reader goes to that heading (`EditorSession.heading`, `ReadAt` in `NoteScreen`); the number counts `#` headings only, so a setext heading shifts it. A week note in `Calendar/` has no Edit (`CalendarWeeks.isWeekNote`).
- **A task's box and a date chip are drawn, not written** (`look.md`, "Editor: the task box" and "Editor: date chips"). `EditorMarkup` reads a task on its line (`EditorLine.task`: the box's range, the status, the chips, the words a closed task strikes), and `EditorTasks` (core/markdown) holds the rules: which icon and colour a status has, when a due date is late, when a box or a chip is raw. `EditorStyler` hides the box's marks and paints the icon behind (`EditorDecor.TaskBox`), and sets a chip's text smaller in its colour with a pill behind (`EditorDecor.Chip`). `TaskSymbols` is the one table of statuses beyond the four; the reader's `customStatus` reads it too.
- **Two of the box's marks keep its room.** They are transparent monospace letters, each sized so the two are 1.1 em wide (`EditorLook.boxScale`, measured in `rememberEditorLook`). One letter that wide would be larger than the text, and a larger letter pushes the baseline of the whole line down by a few pixels, though the line height is fixed. The same holds for anything else that keeps room: never larger than the text.
- **A day after right-to-left text reads `30-10-2026` unless a Latin letter is before it.** Digits after an Arabic letter are Arabic numbers to the bidi rules, and the hyphens between them take the line's direction. `EditorStyler.withLetters` draws the space inside such a chip as a transparent `l`, so the day is `2026-10-30`. It is the one place where the drawn text has another character than the note, at the same place and of the same length. A task's line also takes its direction from its words (`EditorLine.rtl` is read after the box), because the `x` of `[x]` is a Latin letter.
- **A tap on a box is taken in the `Initial` pass** (`taskTaps` in `NoteEditor`), before the text field's own pointer input, so the caret does not move and the keyboard does not open. The target is 40 dp, so a tap on the first letter after a box is the box's. With the caret in the box's marks there is no box to tap. Only what is on screen is painted (`seen`), and `boxAt` asks only the boxes of the lines round the press.
- **A button is `EditorSession.run(EditorEdit)`**, which is one undo step: the format bar's buttons (`EditorCommands.wrap`, `link`), a tap on a box (`toggleTask`, which is `TaskLine.toggled`) and the status sheet (`setStatus`). A new button goes through `run`, or it has no undo. `EditorHistory` (core/markdown) keeps the steps; typing is a step a word, a change of more than one character (a paste, a swiped word) is a step of its own, and so is a turn from writing to deleting. `EditorCommands.wrap` counts the run of a mark's character on each side whole, inside and outside the selection, and takes out only the mark's own characters. The text field's change is `EditorSession.change`, never a write to `value`.
- **The format bar is in the page's column, under the text**, so `imePadding` puts it over the keyboard. It is shown for a selection inside one line (`EditorSession.selectionInLine`) while `editor.format_bar` is on.
- **A line's direction is its words'.** A task reads as the text after its box and a callout's title line as its title (`EditorLine.rtl`), and the paragraph is set that way outright: the `x` of `[x]` and the kind in `[!note]` are the first letters of the drawn text, though they are not seen.
- **Not built**: the room above and under a callout's lines, a return to the editor's exact line when it closes (it is the heading), the soft change, a reason asked for when the status sheet cancels a task, and a way for TalkBack to reach a task's box.

## Public-repo hygiene

- **All test fixtures are synthetic.** Real vault paths leak plenty on their own — folder names alone disclose employers, relationships and where someone lives.
- **No tokens, ever.** The access token is runtime-only: never a `BuildConfig` field, never in `local.properties`, never in CI.
- Screenshots in `fastlane/metadata/` are outward-facing. Treat them the way you would treat a screen shared in a talk.

## Assorted traps, each paid for once

- **SSH host keys are pinned to GitHub's published ones** in `PinnedHostKeys`, for `github.com` and `[ssh.github.com]:443`. For a long time a comment claimed the key was "accepted on first use and pinned", while the code accepted every key on every connection. If GitHub rotates its keys, SSH sync fails with the fingerprint it was shown; update the list from `api.github.com/meta` and check it against the fingerprints on GitHub's docs page (`PinnedHostKeysTest` asserts them).
- **`runCatching` and `catch (e: Exception)` swallow cancellation.** `CancellationException` is an `IllegalStateException`, so a cancelled sync was logged as the vault failing, the next vault synced anyway, and the worker returned a retry. Around suspending work use `runCatchingUnlessCancelled` (in `Failures.kt`), or catch and rethrow `CancellationException` before the broad catch. Blocking calls need their own route to cancellation: OkHttp calls are enqueued and cancelled with the coroutine, and JGit asks `GitProgress.isCancelled`, which is bound to the job.
- **JGit's `repository.resolve(sha)` does not look for the object.** A full 40-character sha resolves whether or not the commit is in the clone, and `parseCommit` then throws `MissingObjectException`. Ask `repository.objectDatabase.has(id)` first. A vault switched from REST carries exactly such a commit.
- **A deploy key belongs to exactly one repository.** GitHub returns 422 "key is already in use" on the second. Hence one key per vault, generated on the device.
- **A global gitignore can hide a whole directory.** A bare `Icon` pattern matched `ui/icon/` on a case-insensitive filesystem, so an entire package was never committed and CI had never run its tests. `!icon/` in `.gitignore` is the fix, and the symptom is a tree that builds locally and not in CI.
- **Nothing is shared across a `Dialog`.** A dialog is another window, and a shared element transition lives inside one `SharedTransitionLayout` in one window. The image viewer was a dialog, and the picture could not grow out of its place in the note until the viewer became a layer over the page, with its own `BackHandler` and a tap sink on the black. The mermaid viewer is still a dialog and has no such transition.
- **The pager shows one document.** Only the tab being read composes `MarkdownDocument`; the page sliding in beside it is a title and an opening line, and becomes the note when the swipe settles. Composing two long notes at once for the length of a swipe is what that avoids; the price is that the preview has to be good enough not to look like a placeholder.
- **Two layouts, one set of tabs.** On an expanded window a note is not navigated to: it is opened into the tabs and the detail pane follows `NoteTabs`. Anything that opens a note from a list goes through `Opening` in `NotesNavHost`, which picks; a new `navController.openNote(...)` call site would show the note full-width beside an empty pane on a tablet.
- **The home page has two places.** On a phone `HomeRoute` is the start destination and no tab's own, so nothing in the bar is lit there. On an expanded window the detail pane with no tab open is the home page, and `HomeRoute` in the list pane draws the browser instead -- otherwise the window shows two homes side by side. The graph is built once, so whether there are two panes reaches it through a `State` (`inTwoPanes`), not a captured `Boolean`.
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
- **Nothing cascades, and `note_fts` is keyed by a note id it cannot check.** There are no foreign keys, so a note removed without its `tasks`, `links`, `headings` and FTS row leaves rows nothing will ever delete -- and still counted: the digest notification totalled orphan tasks. A full reindex once deleted a vault's notes and *then* asked FTS to drop `rowid IN (SELECT id FROM notes WHERE vaultId = ?)`, which by then selected nothing, so every reindex added a whole second copy of the vault to search. `indexAll` is now a diff and every removal goes through `IndexDao.removeNotes`; a new derived table has to be added there.
- **For an SSH vault the working tree *is* the file store.** An edit is written there optimistically before it is sent, and `reset --hard` does not remove an untracked file -- so reading the file back to compute the edit reads the app's own scribble. Read from the commit (`TreeWalk.forPath` on the resolved head), never from disk.
- **A `BackHandler` composed inside a `ModalNavigationDrawer`'s content wins over the drawer's own.** The drawer registers one when it opens, but anything deeper in the composition takes priority, so a screen handler will navigate the page underneath instead of shutting the drawer covering it. Guard it with `!drawerState.isOpen`.
- **Do not enable a drawer's swipe-to-open.** The left edge is the system back gesture. `gesturesEnabled = drawerState.isOpen` keeps swipe-to-close, which is the half worth having.
- **A parser change that alters how many top-level blocks a note makes moves every stored `blockIndex` after it.** Headings and tasks keep their position in the index, and the reader scrolls to what the index says -- so splitting one paragraph into three sends the outline, `[[Note#Heading]]` and a task's "open in note" two blocks short until the note is reindexed. Such a change needs `VaultIndexer.VERSION` to move with it.
- **Compose already gives a small clickable a 48dp touch target.** Hit testing extends any pointer-input node smaller than the minimum to `ViewConfiguration.minimumTouchTargetSize` (48dp) without changing its layout. `minimumInteractiveComponentSize()` does something else -- it grows the *layout* -- which on a task's checkbox would make every line of a task list twice as tall. A test that taps 20dp beside a 16dp icon passes with and without that modifier, which is how this was found out.
- **Robolectric measures text with made-up metrics unless told otherwise.** In its default graphics mode a line of text is not as tall as its font says, so doubling the text size made a bullet 1.33 times taller and a label 1.04 times -- a size test that cannot tell a fix from none. `@GraphicsMode(GraphicsMode.Mode.NATIVE)` on the class draws real text; `TextScaleTest` uses it.
- **A table that hangs off `notes` without a `vaultId` is invisible when orphaned.** `tags` and `aliases` reach their vault through a join on `notes`, so every query over them skips a row whose note has gone -- and a test that asks through those queries passes whether or not `IndexDao.removeNotes` cleared them. Count such a table raw (`SELECT COUNT(*)`) to test that it is emptied.
- **A link found by an alias has to be looked at again when the alias goes.** Resolving only revisits links with no target, so `IndexDao.writeBatch` detaches the links into a note it updates; `resolveLinks`, which always follows it, attaches them again. A path that wrote a batch without resolving afterwards would leave those backlinks missing.
- **Sibling blocks go through `BlockFlattener.blocksOf`, never `children().flatMap { blocksFor(it) }`.** A `^block-id` on a line of its own names the block *before* it, which only the loop over siblings knows; a container flattened the other way leaves that id pending until some unrelated block picks it up. Footnote definitions, callouts, quotes and list items all use `blocksOf`.
- **`navController.graph` throws until the `NavHost` has composed once.** Anything the scaffold around it reads -- the tab bar, say -- runs first, on the first frame, and crashed every launch the one time it asked for the start destination there. Wait for a back stack entry: there is none before the graph exists.
- **Clearing a `LaunchedEffect`'s own key cancels it.** The effect that follows an `obsidian://` link set the link back to null and then suspended to look the note up; the recomposition that null caused cancelled it mid-lookup, and every link opened nothing and said nothing. Consume a one-shot key after the suspending work, not before.
- **ktlint's `no-consecutive-comments`:** inserting a function immediately before another orphans that one's KDoc. Anchor insertions on the `/**`, not on the `@Composable`.
- **An activity is handed its launch intent again on every recreation** -- rotation, a theme or language change, process death, reopening from recents. Anything acted on from `intent` in `onCreate` fires each time unless it is gated on `savedInstanceState == null` and stripped once read. `launchRequest` does both for `MainActivity`.
- **A `NavHost` given a new `startDestination` builds a new graph and resets the back stack.** Feeding it from a settings flow threw the person out of Settings the moment they changed the start screen, and a flow's `initialValue` made the first frame build the wrong graph. Read it once (`rememberSaveable`), and hold the splash until the settings have loaded rather than drawing from defaults.
- **`context.getString` inside a composition fails lint** (`LocalContextGetResourceValueCall`): it is not configuration-aware. Read strings for a toast or a snackbar through `LocalResources.current`; keep `LocalContext` for the `Toast` itself.
- **A heading to scroll to has to name the note it is in.** The note screen resolves `pendingHeading` against whatever note is loaded when the value changes, and drops it if that note lacks the heading. Set it and switch tabs in the same breath and it is looked for in the note being left. `headingIn` pairs it with its note id and hands it over only once that note is on screen; the bookmark and `NoteRoute.heading` paths use it.
- **The build tools unpack an asset named `.gz`.** AAPT decompresses it and drops the extension, so `assets.open("x.gz")` finds nothing on the phone while every JVM test that reads the file from disk passes. The dictionary is committed as plain text for that reason; the APK deflates it anyway. `unzip -l` the APK to see what an asset is really called.
- **Android's regex engine is ICU, not the JVM's.** A bare `}` is a literal to `java.util.regex` and a syntax error to ICU, so `Regex("""\{\{(.*)}}""")` passes every JVM and Robolectric test and throws `PatternSyntaxException` on the phone -- in a companion object, which takes the whole class down with it. Escape every literal brace, and try a new pattern on a device.
- **Lint only checks resources, not Kotlin.** A `Text("Status")` or a `"$n overdue"` built in a view model or a widget compiles, passes lint, and shows English on a Persian phone. View models say which resource with `UiText`; widgets use `context.getString`. `grep -rn 'Text("' app/src/main` is the check.
