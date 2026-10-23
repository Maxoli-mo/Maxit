// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubSkillUrlTest {

    @Test
    fun `parses owner slash repo`() {
        val source = parseGitHubSkillUrl("anthropics/skills")
        assertEquals(SkillSource.GitHub("anthropics", "skills", "main", ""), source)
    }

    @Test
    fun `parses owner repo path assuming main`() {
        val source = parseGitHubSkillUrl("anthropics/skills/skills/pdf")
        assertEquals(SkillSource.GitHub("anthropics", "skills", "main", "skills/pdf"), source)
    }

    @Test
    fun `parses full https url`() {
        val source = parseGitHubSkillUrl("https://github.com/anthropics/skills")
        assertEquals(SkillSource.GitHub("anthropics", "skills", "main", ""), source)
    }

    @Test
    fun `parses tree ref and path`() {
        val source = parseGitHubSkillUrl("https://github.com/owner/repo/tree/dev/path/to/skill")
        assertEquals(SkillSource.GitHub("owner", "repo", "dev", "path/to/skill"), source)
    }

    @Test
    fun `strips trailing slash`() {
        val source = parseGitHubSkillUrl("https://github.com/owner/repo/")
        assertEquals(SkillSource.GitHub("owner", "repo", "main", ""), source)
    }

    @Test
    fun `returns null for too few segments`() {
        assertNull(parseGitHubSkillUrl("onlyowner"))
        assertNull(parseGitHubSkillUrl(""))
        assertNull(parseGitHubSkillUrl("   "))
    }

    @Test
    fun `parses a branch link at repository root`() {
        assertEquals(SkillSource.GitHub("owner", "repo", "dev", ""), parseGitHubSkillUrl("https://github.com/owner/repo/tree/dev"))
    }

    @Test
    fun `rejects wrong hosts traversal and query fragments`() {
        listOf("https://evil.example/owner/repo", "owner/repo/../secret", "owner/repo/tree", "owner/repo?token=secret", "owner/repo/path#fragment").forEach {
            assertNull(parseGitHubSkillUrl(it), it)
        }
    }
}
