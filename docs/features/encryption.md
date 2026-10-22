<!-- Modified for Maxit, 2026-10-20. -->

# Encryption & Secure Storage

**Last verified:** 2026-10-20

App settings, including API keys and email passwords, use a platform-specific settings store. Android and Linux encrypt that store; Web uses unencrypted browser localStorage. The `createSecureSettings` API name does not guarantee encryption on every platform.

Conversation storage is separate: Android and Linux use ordinary SQLite without application-level encryption. Web stores conversations in localStorage, also without encryption. OS access controls and any device encryption provide additional protection, but the app does not encrypt these conversation stores. See [Chat & Conversations](chat.md) for the storage layout and migration chain.

Android, Linux, and Web are maintained. The iOS, macOS, and Windows implementations described below are retained source only.

## Sensitive Data

The following data is stored in the platform settings store (unencrypted on Web):
- Service API keys (per-instance)
- Email account passwords
- MCP server request headers (e.g. authorization tokens), stored with the server list
- Encryption key for legacy conversation migration
- Conversation history on the browser build only

## Platform Implementations

### Android
- **Mechanism:** `EncryptedSharedPreferences` via `dev.spght:encryptedprefs-ktx` (community fork of deprecated `androidx.security:security-crypto`)
- **Key encryption:** AES-256-SIV
- **Value encryption:** AES-256-GCM
- **Key management:** Android Keystore (`MasterKey` with `AES256_GCM` scheme)
- **Size limit:** ~2 MB per value (SharedPreferences limit)
- **File location:** App-private `kai_secure_prefs` SharedPreferences
- **Restore recovery:** If the encrypted file cannot be opened (typically after Android Auto Backup restores it onto a device whose hardware-bound Keystore key did not transfer), the file is deleted and a fresh empty store is created — settings and keys must be re-entered.
- **Legacy migration:** On first run, values from the old plaintext `com.maximiliano.maxit_preferences` (selected service, model ids, API keys, base URL, app-open counter) are copied into the encrypted store once.

### iOS
- **Mechanism:** `KeychainSettings` (`com.russhwolf/multiplatform-settings`)
- **Protection:** Provided by iOS Keychain Services; this app does not select a Keychain encryption algorithm or require Secure Enclave hardware
- **Key management:** Managed by iOS Keychain Services
- **Size limit:** Effectively unlimited
- **Service identifier:** `com.maximiliano.maxit`
- **Legacy migration:** On first run, the same legacy keys are copied once from `NSUserDefaults`.

### Desktop (macOS, Windows, Linux)
- **Mechanism:** `EncryptedFileSettings` — custom file-backed `Settings` implementation
- **Encryption:** AES-256-GCM via `javax.crypto`
- **Key management:** 256-bit random key generated via `SecureRandom`, stored at `~/.kai/settings.key`
- **Limit:** The key is stored beside the encrypted settings. Anyone who can read both files can decrypt the settings; this is not a system keyring or password-protected vault.
- **IV:** 12-byte random IV per write, prepended to ciphertext
- **Authentication tag:** 128-bit GCM tag (integrity + authenticity)
- **File format:** `[12-byte IV][AES-GCM ciphertext + tag]`
- **File location:** `~/.kai/settings.aes`
- **Write safety:** Every change re-encrypts the whole store and writes it to a temporary file that is atomically renamed over the old one, so a crash mid-write never leaves a truncated file. Concurrent writers are serialized. The key and settings files are restricted to owner read/write.
- **Unreadable file:** If the settings file fails to decrypt (wrong or missing key, truncation, tampering), it is renamed aside to `settings.aes.unreadable-<timestamp>` and the app starts with empty settings, so the original is never overwritten.
- **Size limit:** None (file-based)
- **Migration:** On first run, migrates existing data from Java Preferences (`Preferences.userRoot()`) and clears the old store. Java Preferences was the previous backend but has an 8 KB per-value hard limit.

### Web (WASM)
- **Mechanism:** `StorageSettings` (browser `localStorage`)
- **Encryption:** None, including API keys and conversation history. Browser origin isolation restricts access by other sites, but same-origin scripts can read localStorage.
- **Size limit:** ~5 MB total (browser-dependent)

## Legacy Conversation Storage

Prior to the current database-backed storage, conversations were stored in an encrypted file (`conversations.enc`) using XOR encryption with a 32-byte random key, and later as a JSON blob in secure settings. Both are migrated automatically on first load:

1. Read `conversations.enc` from the app files directory
2. Decrypt using XOR with the key from `encryption_key` in settings
3. Persist to the current conversation storage (database, or settings blob on the browser build)
4. Delete the legacy file

On database-capable platforms, a conversation blob found in secure settings is likewise imported into the database and removed from settings on first load.

The XOR encryption key is retained in settings for any devices that haven't migrated yet.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../Platform.kt` | `expect fun createSecureSettings()` declaration |
| `composeApp/src/androidMain/.../Platform.android.kt` | Android EncryptedSharedPreferences setup |
| `composeApp/src/iosMain/.../Platform.ios.kt` | iOS KeychainSettings setup |
| `composeApp/src/desktopMain/.../Platform.jvm.kt` | Desktop EncryptedFileSettings wiring |
| `composeApp/src/desktopMain/.../data/EncryptedFileSettings.kt` | AES-256-GCM file-backed Settings implementation |
| `composeApp/src/wasmJsMain/.../Platform.wasmJs.kt` | Web localStorage setup |
| `composeApp/src/commonMain/.../data/ConversationStorage.kt` | Legacy XOR migration logic |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Settings access layer for all app data |
| `composeApp/src/commonMain/.../data/AppSettingsMigrations.kt` | One-time copy from legacy plaintext settings (Android/iOS) |
| `composeApp/src/commonMain/.../data/ConversationPersistence.kt` | Database vs. settings-blob conversation storage; imports a settings blob into the database |
