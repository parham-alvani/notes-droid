<div align="center">
   <h1>Notes Droid</h1>
   <img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/parham-alvani/notes-droid/ci.yaml?style=for-the-badge&logo=github">
   <img alt="License" src="https://img.shields.io/github/license/parham-alvani/notes-droid?style=for-the-badge">
</div>

## Introduction

An Android reader for an [Obsidian](https://obsidian.md) vault kept in a Git repository. It syncs the vault's markdown directly from GitHub — no server in between, no Obsidian account — and renders it the way Obsidian does, including the parts plain CommonMark gets wrong.

It is **read-only by design**. Editing on a phone means merge conflicts, and the thing worth having on a phone is the reading.

## Status

All seven milestones are implemented, and the app has been running against a real vault — roughly 2,300 notes — on a real device. It is used daily by one person on one phone, which is the honest scope of the field testing behind it.

## Features

- **Offline-first** — the vault lives on the device, so everything works with no connection
- **Incremental sync** — a refresh with nothing upstream costs a single HTTP request, and a renamed note transfers no bytes at all
- **Markdown-only by default** — images are fetched on demand, so a default install is a fraction of the repository's size
- **Obsidian flavour** — `[[wikilinks]]` with Obsidian's own resolution rules, callouts, `==highlight==`, wiki-embeds, tables, task lists
- **Backlinks, unlinked mentions and an outline** for every note
- **Home screen widgets** — what is overdue and due today, and the notes you were last reading
- **Full-text search** over the whole vault with ranked results and highlighted excerpts
- **Folder notes** — a folder's `X/X.md` is its landing page, with the folder's contents beside it
- **Tasks** — every open task in the vault on one screen, grouped by when it is answerable, with an optional daily summary
- **Several repositories** — each mounted at a folder, searched and linked as one vault
- **Attachments** — PDFs read in place; everything else opens in whatever app handles it
- **The vault's own icons** — the assignments from the [Iconic](https://github.com/gfxholo/iconic) plugin, glyphs and colours included
- **Right-to-left support**, detected per block rather than declared — in the note, and in every list that shows a line taken out of one

## Requirements

- JDK 17 — AGP 9 requires it and rejects anything newer
- Android SDK platform `android-37.0` and build-tools `37.0.0`
- [`just`](https://github.com/casey/just)

On a fresh machine:

```bash
just sdk        # installs the command-line tools, platform, build-tools and accepts licences
just doctor     # confirms the toolchain is usable
```

Then point Gradle at the SDK:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

## Building

```bash
just build      # debug apk
just install    # install on the connected device
just run        # install and launch
just test       # unit tests
just lint       # ktlint + android lint
just ci         # everything CI runs
```

## Configuration

The app is told which repository to read at runtime — nothing about the vault is compiled in.

| Setting | Meaning |
| --- | --- |
| Repositories | One or more, each with an owner, a name and a branch |
| Folder | Where a repository appears in the tree. The first mounts at the root |
| Sync method | Per repository: `REST` with a token (default), or `git over SSH` with an on-device key |
| Access token | A GitHub fine-grained personal access token (REST only) |
| Images | `on demand` (default), `prefetch on Wi-Fi`, or `never` |
| Background sync | Off, or every 1/3/6/12/24 hours, optionally Wi-Fi only |
| Daily task summary | Off by default; a single notification counting what is overdue and due |

### Access token

A private vault needs a token. Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new) and give it as little as possible:

- **Resource owner** — the user or organisation that owns the vault
- **Repository access** — *Only select repositories*, and pick just the vault
- **Permissions** — **Contents: Read-only**. `Metadata: Read-only` is added automatically. Nothing else is needed, and nothing else should be granted.

The token is held in a `DataStore` encrypted with an AES-256-GCM key that lives in the AndroidKeyStore and never leaves the device. It is never written to the build, to logs, or to this repository.

Fine-grained tokens expire after a year at most, so expect to replace it; the app has a distinct "token expired" state so the reason is obvious when it happens.

## How sync works

The first sync reads the repository tree at `HEAD`, keeps the markdown blobs, and writes them to the device — ~2,000 files is a few minutes and it resumes cleanly if interrupted.

Afterwards each refresh asks only whether `HEAD` moved, using an `ETag`. If it did not, that is the entire sync: one request, and a `304` does not even count against the rate limit. If it did, a single compare call returns exactly what changed, including renames, which cost nothing to apply because the content is unchanged.

Images are recorded but not downloaded, so the default install carries the markdown alone. They are fetched individually when a note that embeds one is opened, and cached after that.

### Several repositories

Each repository is mounted at a folder and the app treats the result as one vault: the browser shows them side by side, search covers all of them, links resolve across them, and the task list is the union.

The first repository mounts at the root, so a vault that has only ever read one is unchanged — nothing moved on disk and no path was rewritten. Every repository after it needs a folder name, because two of them cannot both own the top level.

**A key per repository, not one for all of them.** GitHub allows a deploy key on exactly one repository — registering the same public line a second time is refused outright — so the app generates one key per repository and Settings lists them separately. The alternative, a key on the account rather than the repository, would be one key and would also grant write access to everything the account can reach, which is a poor trade for something that only reads.

An access token has no such limit: a fine-grained token can name several repositories at once, so REST is the lighter option when a vault spans a few of them.

A repository that fails to sync does not stop the others, and its error is shown against it rather than as the vault's.

### git over SSH

The alternative transport, chosen in Settings. The key is generated on the device and its private half never leaves; the public line is added to the repository as a **read-only deploy key**, so nothing expires and nothing secret is ever copied between machines.

The trade is size. Git cannot fetch a subset of paths — there is no sparse-checkout or partial clone in JGit — so this is the full history and every attachment, where the REST transport takes the markdown alone. The clone is shallow, which helps but does not close the gap. REST remains the default.

### Icons

If the vault uses Obsidian's [Iconic](https://github.com/gfxholo/iconic) plugin, the browser, search results and a note's own header show the icons it assigns, in the colours it assigns them.

The assignments are read from the vault itself, at `.obsidian/plugins/iconic/data.json` — the one file under a dot-directory that syncs, because it is part of how the vault reads rather than editor configuration. Changing an icon in Obsidian and committing is all it takes; nothing is compiled in.

Glyphs come from [Lucide](https://lucide.dev), flattened into a single asset by `tools/lucide.py` rather than shipped as 2,112 drawables. Emoji assignments render as themselves. An icon the set does not have falls back to the default rather than leaving a gap.

## Milestones

- [x] **M1** — sync: token storage, GitHub client, incremental planner, resumable first sync
- [x] **M2** — reader: markdown rendering, folder browser, note screen
- [x] **M3** — vault: wikilinks, embeds, callouts, backlinks, outline
- [x] **M4** — search: full-text index with ranking and excerpts
- [x] **M5** — hard content: images, diagrams, maths, right-to-left
- [x] **M6** — release: background sync, settings, permissions
- [x] **M7** — git-over-SSH as an alternative transport
- [x] **M8** — the vault's own icons, from the Iconic plugin
- [x] **M9** — background sync that actually runs, and releases signed from a tag
- [x] **M10** — attachments: PDFs read in place, everything else handed off
- [x] **M11** — tasks across the whole vault, with a daily summary
- [x] **M12** — several repositories, mounted side by side
- [x] **M13** — unlinked mentions, widgets, an SSH panel that tests a key
- [x] **M14** — syntax colouring for the languages the tokeniser has no grammar for

Not done: an onboarding flow (setup lives in Settings instead), and editing, which remains out of scope.

## Releasing

Releases are cut from a tag and built by CI. Nothing is published by hand.

```bash
just bump 0.2.0                 # versionName, versionCode, changelog stub
$EDITOR fastlane/metadata/android/en-US/changelogs/200.txt
git commit -am "chore: release 0.2.0"
git tag v0.2.0 && git push --follow-tags
```

`versionCode` is derived from the version — `0.2.0` is `200`, `1.12.3` is `11203` — so it can never go backwards. Both are literals in `app/build.gradle.kts` rather than computed, because F-Droid reads them out of that file to decide an update exists. The workflow refuses a tag that disagrees with what the file says.

### Signing

An app's identity is its signing key. Change it and the only way to update an installed copy is to uninstall it, which takes the synced vault, the access token and the on-device SSH key with it. So the key is generated once and kept:

```bash
keytool -genkeypair -v -keystore daftar-release.jks \
    -alias daftar -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` and its passwords somewhere they survive losing the machine. Locally, point the build at them with a `keystore.properties` at the repository root — gitignored, alongside `*.jks`:

```properties
storeFile=/absolute/path/to/daftar-release.jks
storePassword=…
keyAlias=daftar
keyPassword=…
```

In CI the same four values come from repository secrets `KEYSTORE_BASE64` (`base64 -w0 daftar-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. **With none of them present the release build falls back to the debug key**, which is what keeps this repository buildable by anyone who clones it — and is why the release workflow refuses to run without `KEYSTORE_BASE64` rather than quietly publishing something that can never be updated.

Every release carries its certificate's SHA-256 fingerprint in the notes. Check it matches the copy you already have before installing an update:

```bash
apksigner verify --print-certs daftar-0.2.0.apk
```

### F-Droid

`fdroid/me.parham1995.notes.yml` is the metadata to submit as a merge request against [fdroiddata](https://gitlab.com/fdroid/fdroiddata). The description, changelogs and screenshots are not in it — F-Droid reads those from `fastlane/metadata/` in this repository, so there is only one copy of that text.

Two things to know before submitting:

- The app is declared `NonFreeNet`. It reads the vault from GitHub and cannot be used without an account there. That is accurate, and declaring it is better than a reviewer finding it.
- **`mermaid.min.js` is the likely sticking point.** F-Droid's scanner flags it as a binary in the source tree, correctly — minified output is not the preferred form for modification. It is the unmodified MIT-licensed npm dist, and the metadata carries a `scanignore` for it, but expect pushback. The ways out, in increasing order of effort: argue the exception, build Mermaid from source in a `prebuild` step, or ship diagrams as plain code blocks in the F-Droid build.

F-Droid signs with its own key, so an F-Droid install and a GitHub-release install are different app identities and cannot update each other.

## Testing

```bash
just test    # JVM unit tests: parser, link resolver, sync planner, transport
just lint    # ktlint + Android lint (warnings are errors)
just ci      # everything CI runs
```

There are no instrumentation tests and CI runs no emulator. The markdown and sync modules are pure JVM by construction, which is the point of keeping them free of Android — their tests run in seconds.

## Related

- [notes-cli](https://github.com/parham-alvani/notes-cli) — cleans up and optimises images in the same vault

## License

[GPL-3.0](LICENSE)
