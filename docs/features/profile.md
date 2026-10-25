# Profile

**Last verified:** 2026-10-25. Automated verification is recorded in [System Prompts](system-prompts.md); live Android and model behavior still need acceptance testing.

Settings → **Profile** groups personal names and response preferences without making them one save scope. It uses two cards side by side on wide screens and stacked on narrow screens, with a separate visible Save button in each. No avatar or account service is required.

## Personal names

- **Full name** is optional, stored only as profile metadata, and is not included in the model's system prompt. This does not remove names the user previously shared in chats, memories, or custom instructions.
- **What should we call you?** is the optional preferred conversational name. It is provided to the model independently of memory, so disabling memory does not disable it. Guidance tells the model to use it for conversational address even when memory contains a different name, without inserting it into every reply.
- A preferred form of address is not evidence of legal identity. Documents, signatures, and other identity-sensitive work need the appropriate name for that task; Maxit must not substitute the conversational name automatically.
- Both fields accept up to 200 characters of single-line text without control characters. Surrounding whitespace is trimmed on Save. Both can be cleared.
- **Save profile** persists only the two names. It does not save or discard a custom-instruction draft.

## Custom instructions

- The field is empty by default, with examples as placeholder text rather than saved content.
- Instructions apply across conversations and supplement built-in behavior; they do not replace product knowledge, honesty, or authorization rules. Explicit instructions for the current request take precedence over conflicting standing preferences.
- **Save instructions** persists only this field, trimming surrounding whitespace. It does not save or discard a name-field draft.
- The editor allows 4,000 characters. Existing longer imported instructions remain intact and can be shortened rather than being truncated automatically.
- Clearing the field and saving removes custom instructions, leaving built-in behavior and names intact. Existing legacy instructions are preserved without rewriting or copying the built-in prompt into the editor.

## Persistence and privacy

Profile uses the app's existing settings storage. No names are inferred from memories, screenshots, accounts, or device information to prefill these fields. Only the preferred name is passed to prompt assembly, as quoted data rather than an instruction.

Profile and Custom instructions are independent selectable [backup sections](settings-export-import.md). A Profile export contains both names, including Full name, so the human-readable backup is personal data. Import validates both names before changing either; malformed data leaves the saved profile intact. Replace resets unselected sections; Merge preserves them.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../ui/settings/ProfileSettings.kt` | Independent name and custom-instruction forms |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Profile category and responsive settings layout |
| `composeApp/src/commonMain/.../ui/settings/SettingsUiState.kt` | Saved profile and custom-instruction state |
| `composeApp/src/commonMain/.../ui/settings/SettingsActions.kt` | Separate save actions |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Independent persistence and state updates |
| `composeApp/src/commonMain/.../data/UserProfile.kt` | Name validation and profile record |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Settings persistence and backup-section discovery |
| `composeApp/src/commonMain/.../data/AppSettingsImportExport.kt` | Selective backup and validated import |
| `composeApp/src/commonMain/.../data/RemoteDataRepository.kt` | Passes only the preferred name into prompt construction |
| `composeApp/src/commonMain/.../data/ChatSystemPromptBuilder.kt` | Preferred-address guidance and additive custom instructions |
| `composeApp/src/commonMain/composeResources/values/strings.xml` | English labels and privacy explanations |
| `composeApp/src/commonMain/composeResources/values-pt/strings.xml` | Portuguese labels and privacy explanations |
| `composeApp/src/commonTest/.../data/UserProfileTest.kt` | Persistence, backup scope, and invalid-import regressions |
| `composeApp/src/commonTest/.../ui/settings/SettingsViewModelTest.kt` | Independent Save behavior |
| `composeApp/src/desktopTest/.../ui/settings/ProfileSettingsLayoutTest.kt` | Offscreen responsive rendering and independent unsaved drafts |
| `composeApp/src/desktopTest/.../data/SignedOutCodexWorkTest.kt` | Real prompt assembly excludes the profile full name |
