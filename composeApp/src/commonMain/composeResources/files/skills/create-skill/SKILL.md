---
name: create-skill
description: Create, self-review, and install a reusable Maxit skill, with bundled files when needed, at the user's explicit request.
---

<!-- Modified for Maxit, 2026-10-23. -->

Create a reusable skill only when the user asks to create or save one. Do not turn ordinary questions or one-off tasks into persistent skills. Ask one clarifying question only if the intended behavior is unclear.

## Draft

1. **Define the behavior.** Identify when the skill should activate, its inputs, expected output, constraints, and relevant failure cases.
2. **Pick an unused id.** Compare with the installed-skills catalog. Use lowercase letters, digits, and hyphens; at most 64 characters. Never replace an installed skill or built-in.
3. **Write the manifest.** Include `name` and a non-empty description of at most 1,024 characters in YAML frontmatter, followed by non-empty Markdown instructions. The description must say when to use the skill, not merely repeat its name. Keep the instructions focused, actionable, and consistent with the user's request. The complete `SKILL.md` must fit within 64,000 characters.
4. **Bundle only necessary files.** Scripts, reference documents, and templates belong in `files`, each with a safe relative `path` and literal `content`. Text defaults to `encoding: "utf8"`; use `encoding: "base64"` only for known binary bytes, not invented assets or a host-file path. Do not put `SKILL.md` in `files`. Limits: 4 MiB per decoded file, 16 MiB for the entire bundle, and 256 files including the manifest.

Inspect the actual `create_skill` schema before bundling. A string-only local-model schema exposes only `markdown`. If needed files cannot be supplied through that schema, explain the limitation and offer a remote model or ZIP import; do not silently omit required files, claim a complete bundle, or bypass it through shell writes. Instruction-only skills still work with `markdown` alone.

## Pre-save self-review

Before calling `create_skill`, reread the complete instructions **and every bundled file** as the future model consuming the skill. Check and fix:

- **Intent and activation:** Does it do what the user requested? Does the description identify appropriate triggers without making the skill activate for unrelated work?
- **Instructions:** Are inputs, outputs, ordering, edge cases, and failure handling clear? Remove contradictory steps, unnecessary questions, and unrequested side effects.
- **Formatting:** Check the frontmatter delimiters, name/description limits, non-empty body, headings, code fences, and example syntax. Do not pass an outer Markdown fence to the creation tool.
- **Tool and runtime availability:** Reference actual available tools by their names. Declare required runtimes/dependencies and unsupported platforms instead of pretending they exist. Skills cannot grant permissions or enable tools.
- **File references:** Every promised bundled path must be present with the correct spelling and contents. Use relative paths; reject absolute paths, traversal, duplicate names, and extra manifests. Explain how to read references with `load_skill(id, path)`. Runtime scripts must locate sibling files relative to their own file, not assume a particular working directory or `/root` host path. Invoke scripts through their interpreter; executable permission bits are not preserved.
- **Code and data:** Check imports, syntax, inputs/outputs, dangerous operations, credentials, and whether binary assets are genuinely valid for their stated format. Include only files the skill needs.
- **Example walkthrough:** Reason through one representative request and one likely failure case. Confirm the instructions and file contents support the intended result; fix gaps before saving.

Fix findings and reread the affected parts before creation. This is a model self-review, not an independent audit or proof of correctness. Do not execute generated scripts merely to save a skill. Only claim a syntax check or runtime test passed if that exact check actually ran with appropriate authorization and available tools; otherwise say the bundle was reviewed but not executed/tested.

## Save and confirm

Call `create_skill` with raw `SKILL.md` in `markdown` and optional bundled `files`. For example:

```json
{
  "markdown": "---\nname: meeting-notes\ndescription: Turn meeting transcripts into decisions and action items.\n---\nRead references/output.md with load_skill before formatting the notes. Summarize only decisions and actions supported by the transcript.",
  "files": [
    {
      "path": "references/output.md",
      "content": "## Decisions\n- Decision and supporting context\n\n## Action items\n- Task, owner, and deadline; mark unknown values explicitly.\n"
    }
  ]
}
```

Only after `create_skill` returns success, confirm the installed id and `/<id>` command, summarize the review and any testing limitations briefly, and mention bundled files when present. Settings and the slash menu refresh without a restart or sandbox setup. Creation never executes the skill, installs dependencies, or tests its behavior.

For a duplicate id, choose an unused one rather than overwrite. Fix format, path, size, or encoding errors and retry the complete bundle. If creation is unavailable or permission is denied, explain that installation did not succeed. You may provide files for manual import, but do not bypass the failure with shell commands or another write path.
