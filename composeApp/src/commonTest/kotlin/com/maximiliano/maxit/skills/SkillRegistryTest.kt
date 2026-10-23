package com.maximiliano.maxit.skills

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(io.ktor.utils.io.InternalAPI::class)
class SkillRegistryTest {
    private val markdown = "---\nname: sample\ndescription: Example\n---\nUse this skill."

    private fun client(responses: Map<String, ByteArray>, requests: MutableList<String> = mutableListOf()): HttpClient = HttpClient(
        object : HttpClientEngineBase("skill-test") {
            override val config = HttpClientEngineConfig()
            override val dispatcher = Dispatchers.Unconfined
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val url = data.url.toString()
                requests.add(url)
                val body = responses[url]
                return HttpResponseData(
                    statusCode = if (body != null) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    requestTime = GMTDate(),
                    headers = headersOf(),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = ByteReadChannel(body ?: byteArrayOf()),
                    callContext = callContext(),
                )
            }
        },
    )

    @Test
    fun `root repository installs retain nested binary files`() = runTest {
        val binary = byteArrayOf(0, -1, 3)
        val tree = """{"tree":[{"type":"blob","mode":"100644","path":"SKILL.md"},{"type":"blob","mode":"100644","path":"assets/icon #1%.bin"}]}"""
        val http = client(
            mapOf(
                "https://api.github.com/repos/owner/repo/git/trees/main?recursive=1" to tree.encodeToByteArray(),
                "https://raw.githubusercontent.com/owner/repo/main/SKILL.md" to markdown.encodeToByteArray(),
                "https://raw.githubusercontent.com/owner/repo/main/assets/icon%20%231%25.bin" to binary,
            ),
        )
        try {
            val skill = SkillRegistry(http).fetchSkillFiles(SkillSource.GitHub("owner", "repo", path = "")).getOrThrow()
            assertEquals("sample", skill.id)
            assertContentEquals(binary, skill.files.getValue("assets/icon #1%.bin"))
        } finally {
            http.close()
        }
    }

    @Test
    fun `folder downloads ignore files outside the chosen skill`() = runTest {
        val requests = mutableListOf<String>()
        val tree = """{"tree":[{"type":"blob","path":"skills/sample/SKILL.md"},{"type":"blob","path":"skills/other/SKILL.md"}]}"""
        val http = client(
            mapOf(
                "https://api.github.com/repos/owner/repo/git/trees/dev?recursive=1" to tree.encodeToByteArray(),
                "https://raw.githubusercontent.com/owner/repo/dev/skills/sample/SKILL.md" to markdown.encodeToByteArray(),
            ),
            requests,
        )
        try {
            assertTrue(SkillRegistry(http).fetchSkillFiles(SkillSource.GitHub("owner", "repo", "dev", "skills/sample")).isSuccess)
            assertFalse(requests.any { "skills/other" in it })
        } finally {
            http.close()
        }
    }

    @Test
    fun `missing bundled downloads and truncated repository trees fail instead of partial installation`() = runTest {
        for (tree in listOf(
            """{"tree":[{"type":"blob","path":"SKILL.md"},{"type":"blob","path":"missing.py"}]}""",
            """{"truncated":true,"tree":[{"type":"blob","path":"SKILL.md"}]}""",
            """{"tree":[{"type":"blob","mode":"120000","path":"SKILL.md"}]}""",
        )) {
            val http = client(
                mapOf(
                    "https://api.github.com/repos/owner/repo/git/trees/main?recursive=1" to tree.encodeToByteArray(),
                    "https://raw.githubusercontent.com/owner/repo/main/SKILL.md" to markdown.encodeToByteArray(),
                ),
            )
            try {
                assertTrue(SkillRegistry(http).fetchSkillFiles(SkillSource.GitHub("owner", "repo", path = "")).isFailure)
            } finally {
                http.close()
            }
        }
    }
}
