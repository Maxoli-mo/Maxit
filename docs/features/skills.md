<!-- Modified for Maxit, 2026-10-22. -->

# Skills

**Last reviewed:** 2026-10-23. All 1,124 isolated desktop tests, two targeted Android host directory-reading tests, Android FOSS/WebAssembly source compilation, and formatting passed. Offscreen Compose checks rendered expanded skill cards at desktop and narrow widths. Live-model selection/creation, real-device migration, and live desktop-profile migration have not been exercised.

Maxit supports installable **skills**: reusable instruction bundles in the [SKILL.md](https://agentskills.io) format. Each bundle contains a name, a description, an instruction body, and optional files.

Skills work on **Android and desktop without the Linux sandbox**. User-installed bundles live at `getAppFilesDirectory()/skills/<id>/`: Android's `filesDir/skills/<id>/` or desktop's `~/.maxit/skills/<id>/`. The sandbox is not their source of truth. Desktop's earlier `~/.kai` directory is copied non-destructively on first use; see [desktop data migration](encryption.md#desktop-data-directory-migration). This is app storage, not a general workspace workflow.

The built-in `create-skill` loads from app resources without sandbox setup and cannot be removed through Settings. At the user's explicit request, it guides the model to draft instructions and needed files, perform a **pre-save self-review**, then call `create_skill(markdown, files?)`. Creation validates and installs the complete bundle on Android and desktop, refreshes Settings/the slash menu, and returns its actual id, command, and bundled-file count. Existing skills and built-ins cannot be overwritten. Saving never executes code, installs dependencies, or verifies the skill's behavior.

The optional `files` array contains objects with `path`, `content`, and optional `encoding`. Paths are safe relative bundle paths such as `scripts/report.py` or `references/output.md`. Content defaults to UTF-8 text; `encoding: "base64"` supports known binary bytes. Files are literal contents, not host paths to copy. Malformed Unicode is rejected in text, manifests, and filenames rather than silently replaced. Duplicate/unsafe paths, extra manifests, invalid encoding, empty instruction bodies, and exceeded bounds fail before publishing the skill; installation is all-or-nothing. Use `markdown` for the single `SKILL.md`, never a bundled file with that name.

The self-review covers user intent, activation conditions, inputs/outputs and failure cases, frontmatter/Markdown formatting, actual tools/runtimes, every file reference and file's contents, code/data safety, and a representative request/failure walkthrough. The model must fix findings before saving and distinguish review from actual checks. This is prompted self-review, not an independent audit, Markdown linter, or runtime test.

Small on-device models retain the original **Markdown-only** creation schema to avoid exposing nested file arguments to their strict parser. Remote models receive the complete bundle schema. The guide must explain when required assets cannot be supplied, offer a remote model or ZIP import, and never claim a complete bundle while omitting them. If creation is unavailable or denied, it may provide files for manual import without claiming installation or bypassing the failure through shell writes.

## Concepts

### Skill

`SKILL.md` frontmatter supplies `name` (the skill id) and `description`; the Markdown below it is the instruction body. Ids use lowercase letters, digits, and hyphens, up to 64 characters. Bundled files can include nested text files and binary assets. There is no per-skill enable/disable state.

### Catalog and lazy loading

The model receives a catalog of installed skill names and descriptions, not every instruction body. It can choose a relevant skill and call `load_skill` with its id to read the body. The same tool accepts a relative bundled-file path to read a text reference from that skill. Reads stay inside the selected bundle; binary assets are preserved on disk but are not returned as text.

The app caches parsed instructions and filenames, not bundled asset bytes. Startup and reload scan file metadata; each requested reference is read from storage with fresh path and size checks. Platforms without skill storage expose neither installed skills nor built-ins in chat.

Installing or loading a skill **does not execute scripts**. Instructions cannot grant tools or permissions the app does not provide. Android shell execution still requires the separately installed and enabled sandbox. Desktop already has an optional shell tool, disabled by default: it executes with the host user's permissions, not in a sandbox. Review bundles from untrusted sources before using them.

### Slash command

A leading `/<skill-id>` explicitly selects that skill for the current turn, overriding automatic skill selection. Matching is case-insensitive. The user's message is sent verbatim, including the slash command and any arguments.

The selected body is appended under an "Active skill" heading for that turn only. This does not pin the skill for later turns. Without an explicit selection, the model can still discover skills through the catalog and load them on demand. Both activation paths supply the same installed-file inventory, bundle directory, and access guidance. Resolve skill-relative asset/script paths against that directory, not the shell's inherited working directory; use absolute paths or explicitly set `working_dir` when appropriate. Keep generated outputs outside the installed bundle, in the user's requested output directory. Bundled text is read with `load_skill`; binary fonts/images require an enabled filesystem/shell tool.

## Installing a Skill

Open **Settings → Tools → Skills → Add Skill** on Android or desktop. No sandbox setup is required. There is no marketplace browser.

- **GitHub:** paste `owner/repo`, `owner/repo/path/to/skill`, `https://github.com/owner/repo`, or a `.../tree/<ref>/path` URL. Point to a single skill folder when a repository contains several skills. Downloads use GitHub's unauthenticated API and require network access.
- **Local Markdown:** import a `.md` file containing valid skill frontmatter and its instruction body. It becomes the bundle's `SKILL.md`. This installs instructions only, not referenced fonts, scripts, or other supporting files. Instructions-only bundles are explicitly identified in the model's skill context; use the complete ZIP or GitHub folder when assets are required.
- **Local ZIP:** import a `.zip` containing exactly one `SKILL.md`, either at the root or inside a wrapper folder. Its containing folder becomes the bundle root. Nested files and binary assets under that root are preserved.

Limits apply to imported and downloaded bundles: **4 MiB per file, 16 MiB total, and at most 256 files**, including `SKILL.md`. ZIP entry limits also count directories, and ZIP64/split archives are unsupported. `SKILL.md` is limited to 64,000 characters, checked before frontmatter parsing; bundled text reads are limited to 64,000 bytes and fail rather than truncate. Archives are bounded by their uncompressed contents, not just the ZIP's download size. Paths must be safe relative paths; absolute paths, traversal (`..`), and unsafe archive entries are rejected rather than written outside the bundle. Unix/macOS ZIP symlinks are rejected rather than installed as files containing link-target text. Normal binary assets and executable-file entries remain importable; imports do not grant execute permissions.

Invalid frontmatter, missing or multiple `SKILL.md` files, unsafe paths, and exceeded limits fail installation. Installing an existing id also fails: it never silently overwrites a user bundle or built-in. Choose a different id, or explicitly remove a user-installed skill before replacing it. Errors appear in the add-skill dialog.

## Existing Android skills

Legacy bundles under the Android sandbox's `~/skills/` are migrated by **copying** them into app-private skill storage. The originals are not deleted. Completion is tracked separately for each distribution, and persistent removal markers prevent a later scan from restoring a skill the user removed—even when a damaged sibling prevented a migration from completing. New installs and reads use app-private storage, not the old sandbox folder.

Migration uses strict directory reads: an inaccessible root or supporting directory fails that copy rather than publishing an asset-free bundle. A missing legacy skills root is allowed. Failed copies leave completion unset so they can be retried after recovery. Successfully copying another distribution's home advances a persisted revision and triggers a new scan, including after an app restart; existing installed IDs and removal markers still take precedence.

A damaged installed bundle is shown as unavailable in Settings so it can be removed; it is excluded from automatic discovery and the slash menu without hiding healthy skills.

Older installations may contain only `SKILL.md` even when the upstream skill has fonts or scripts. Loading cannot recreate missing files. Preserve any customizations and explicitly replace an incomplete installation with the complete GitHub folder or ZIP; the installer never overwrites existing IDs automatically. Referenced-but-unlisted assets must be reported as missing, not silently replaced through downloads.

The Android shell sees the app-owned bundle directory at `/mnt/maxit-skills/<id>/`; desktop tools use the host storage path above. Scripts should be invoked with their interpreter; imports do not preserve executable permission bits.

## Skill Management

Each card shows the slash command and description. Expanding a user-installed card also shows **Instructions only — SKILL.md**, a supporting-file count with a sorted, scrollable filename list, or an unavailable-files warning for damaged bundles. A file count indicates presence, not completeness. Built-ins have a Built-in badge and no remove action or misleading installed-file status.

Removal keeps its four-second snackbar **Undo** window. Add Skill and import submissions are blocked while a skill removal is pending or actually deleting, then re-enabled on Undo or completion. Committed removal continues if Settings is left and reopened. This prevents reimporting the same ID before its old folder has been removed. Preserve customized instructions before replacing an incomplete installation.

## Chat Autocomplete

Typing `/` in the first token opens a filtered list of installed skills above the composer. Selecting one inserts the canonical `/<id> ` and leaves the cursor ready for arguments. The menu only appears while the cursor is in that leading token.

## Limitations

- Android and desktop are covered by this rework; it does not add skill storage/import to Web or iOS.
- Automatic selection requires a model with tool support. Explicit slash activation still supplies the selected instructions without tool calls. If local tool-call parsing fails and the model retries without tools, its retry prompt omits the discovery catalog while retaining explicitly selected instructions.
- A loaded body or text reference consumes model context. The catalog avoids loading every body up front, but large skills still use prompt budget when selected.
- GitHub installs are subject to network failures and unauthenticated API rate limits.
- GitHub shorthand assumes the `main` branch; use a `/tree/<ref>/...` URL for a different branch.
- Binary assets are retained, not executed or exposed as text by `load_skill`. A skill may require tools or runtimes unavailable on the current platform.
- [Interactive UI](../skills/interactive-ui/SKILL.md) remains an archive outside app resources. It is not registered, loaded, or re-enabled by this rework.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../skills/SkillManifest.kt` | Installed skill metadata, downloaded bundles, and GitHub source coordinates |
| `composeApp/src/commonMain/.../skills/SkillFrontmatterParser.kt` | Frontmatter parsing and id validation |
| `composeApp/src/commonMain/.../skills/SkillImport.kt` | Bundle normalization, path validation, and shared size/file-count limits |
| `composeApp/src/commonMain/.../skills/SkillRegistry.kt` | GitHub bundle downloads, including nested and binary files |
| `composeApp/src/commonMain/.../skills/SkillManager.kt` | App-private installs, reads, removal, built-ins, cache, and legacy migration |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Installed catalog and per-turn active-skill section |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Supplies skill context and resolves explicit turn selections |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | Parses the leading slash command into a skill id |
| `composeApp/src/commonMain/.../ui/chat/composables/QuestionInput.kt` | Detects the slash query while typing |
| `composeApp/src/commonMain/.../ui/chat/composables/SkillAutocomplete.kt` | Slash-command dropdown |
| `composeApp/src/commonMain/.../ui/settings/SkillsSection.kt` | Skill cards, GitHub install, and local `.md`/`.zip` import |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Install/import/removal state and errors |
| `composeApp/src/commonMain/.../ui/settings/ToolsSettings.kt` | Skills section on Android and desktop, independent of sandbox state |
| `composeApp/src/commonMain/composeResources/files/skills/create-skill/SKILL.md` | Built-in skill authoring instructions |
