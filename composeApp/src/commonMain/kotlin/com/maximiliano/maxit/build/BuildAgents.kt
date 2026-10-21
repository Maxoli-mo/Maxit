// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The coding agents Maxit Build can install on a fresh Debian. Each one ships a
 * self-contained installer, so the base system plus curl is all they need.
 *
 * Install locations (guest paths under `/root`):
 * - OpenCode → `~/.opencode/bin/opencode`
 *
 * Proot injects agent bin dirs into PATH for probes/installers. Login
 * shells rebuild PATH from profile files, so Maxit Build also writes a
 * profile.d snippet and launches agents by absolute path.
 */
@Immutable
data class BuildAgent(
    val id: String,
    /** Product name — shown as-is, not translated. */
    val title: String,
    val binary: String,
    val installCommand: String,
)

object BuildAgents {

    val all: ImmutableList<BuildAgent> = persistentListOf(
        BuildAgent(
            id = "opencode",
            title = "OpenCode",
            binary = "opencode",
            installCommand = "curl -fsSL https://opencode.ai/install | bash",
        ),
    )

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
