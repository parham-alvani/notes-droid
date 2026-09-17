<div align="center">
   <h1>Notes Droid</h1>
   <img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/parham-alvani/notes-droid/ci.yaml?style=for-the-badge&logo=github">
   <img alt="License" src="https://img.shields.io/github/license/parham-alvani/notes-droid?style=for-the-badge">
</div>

## Introduction

An Android reader for an [Obsidian](https://obsidian.md) vault kept in a Git repository. It syncs the vault's markdown directly from GitHub — no server in between, no Obsidian account — and renders it the way Obsidian does, including the parts plain CommonMark gets wrong.

It is **read-only by design**. Editing on a phone means merge conflicts, and the thing worth having on a phone is the reading.

## Status

Early. The build and module skeleton are in place; the sync layer is the current milestone. See the milestones below for what exists and what does not.

## Features

- **Offline-first** — the vault lives on the device, so everything works with no connection
- **Incremental sync** — a refresh with nothing upstream costs a single HTTP request, and a renamed note transfers no bytes at all
- **Markdown-only by default** — images are fetched on demand, so a default install is a fraction of the repository's size
- **Obsidian flavour** — `[[wikilinks]]` with Obsidian's own resolution rules, callouts, `==highlight==`, wiki-embeds, tables, task lists
- **Backlinks and outline** for every note
- **Full-text search** over the whole vault with ranked results and highlighted excerpts
- **Folder notes** — a folder's `X/X.md` is treated as its landing page
- **Right-to-left support**, detected per block rather than declared, for vaults that mix scripts

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
| Owner | GitHub user or organisation |
| Repository | The repository holding the vault |
| Branch | Defaults to the repository's default branch |
| Access token | A GitHub fine-grained personal access token |
| Images | `on demand` (default), `prefetch on Wi-Fi`, or `never` |

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

> [!note]
>
> Git-over-SSH is planned as an alternative transport for people who would rather use a key than a token. It is a full clone, so it trades the small footprint for not having to rotate a token.

## Milestones

- [ ] **M1** — sync: token storage, GitHub client, incremental planner, resumable first sync
- [ ] **M2** — reader: markdown rendering, folder browser, note screen
- [ ] **M3** — vault: wikilinks, embeds, callouts, backlinks, outline
- [ ] **M4** — search: full-text index with ranking and excerpts
- [ ] **M5** — hard content: images, diagrams, maths, right-to-left
- [ ] **M6** — release: background sync, settings, onboarding
- [ ] **M7** — git-over-SSH as an alternative transport

## Related

- [notes-cli](https://github.com/parham-alvani/notes-cli) — cleans up and optimises images in the same vault

## License

[GPL-3.0](LICENSE)
