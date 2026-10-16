package org.example.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class GitActivityReportTest {
    @Test
    fun `real stdio report is bounded stable excludes sensitive paths and handles dirty detached and truncation`() = runBlocking {
        val root = Files.createTempDirectory("git-activity-report")
        val repository = root.resolve("repository")
        Files.createDirectories(repository)
        git(repository, "init", "-b", "feature/digest")
        git(repository, "config", "user.name", "Digest Test")
        git(repository, "config", "user.email", "digest@example.invalid")

        Files.writeString(repository.resolve("alpha.txt"), "alpha-content-must-not-leak\n")
        Files.writeString(repository.resolve(".env"), "OPENAI_API_KEY=secret-must-not-leak\n")
        Files.writeString(repository.resolve(".llm-task-state.json"), "runtime-state-must-not-leak\n")
        git(repository, "add", "alpha.txt", ".env", ".llm-task-state.json")
        git(repository, "commit", "-m", "Первый безопасный коммит", date = "2026-10-15T08:00:00Z")
        Files.writeString(repository.resolve("zeta.txt"), "zeta-content-must-not-leak\n")
        git(repository, "add", "zeta.txt")
        git(repository, "commit", "-m", "Второй коммит sk-proj-sensitive123456", date = "2026-10-15T09:00:00Z")

        Files.writeString(repository.resolve("alpha.txt"), "staged-content-must-not-leak\n")
        git(repository, "add", "alpha.txt")
        Files.writeString(repository.resolve("alpha.txt"), "unstaged-content-must-not-leak\n")
        Files.writeString(repository.resolve("todo.txt"), "untracked-content-must-not-leak\n")

        val fixedNow = Instant.parse("2026-10-15T10:00:00Z")
        val gateway = LocalMcpGateway(
            root.resolve("scheduler.json"),
            root.resolve("output"),
            repositoryRoot = repository,
            clockInstant = fixedNow,
        )
        try {
            val tool = gateway.listTools().single { it.name == GIT_ACTIVITY_REPORT_TOOL }
            assertEquals(WORKSPACE_SERVER_ID, tool.serverId)
            val properties = tool.inputSchema.getValue("properties").jsonObject
            assertEquals(24, properties.getValue("sinceHours").jsonObject.getValue("default").jsonPrimitive.int)
            assertEquals(20, properties.getValue("maxCommits").jsonObject.getValue("default").jsonPrimitive.int)
            assertEquals(50, properties.getValue("maxFiles").jsonObject.getValue("default").jsonPrimitive.int)

            val first = gateway.callTool(GIT_ACTIVITY_REPORT_TOOL, buildJsonObject {})
            assertFalse(first.isError, first.content)
            val report = Json.parseToJsonElement(first.content).jsonObject
            assertEquals(1, report.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals("feature/digest", report.getValue("branch").jsonPrimitive.content)
            assertEquals(false, report.getValue("detached").jsonPrimitive.boolean)
            assertEquals(2, report.getValue("commits").jsonArray.size)
            assertEquals(listOf("C1", "C2"), report.getValue("commits").jsonArray.map {
                it.jsonObject.getValue("sourceId").jsonPrimitive.content
            })
            val files = report.getValue("files").jsonArray.map { it.jsonObject }
            assertEquals(files.indices.map { "F${it + 1}" }, files.map { it.getValue("sourceId").jsonPrimitive.content })
            assertEquals(
                files.map { it.getValue("scope").jsonPrimitive.content to it.getValue("path").jsonPrimitive.content },
                files.sortedWith(compareBy({ it.getValue("scope").jsonPrimitive.content != "committed" },
                    { it.getValue("path").jsonPrimitive.content }))
                    .map { it.getValue("scope").jsonPrimitive.content to it.getValue("path").jsonPrimitive.content },
            )
            assertTrue(files.none { it.getValue("path").jsonPrimitive.content == ".env" })
            assertTrue(files.none { it.getValue("path").jsonPrimitive.content.startsWith(".llm-") })
            assertFalse(first.content.contains("content-must-not-leak"))
            assertFalse(first.content.contains("secret-must-not-leak"))
            assertFalse(first.content.contains("runtime-state-must-not-leak"))
            assertFalse(first.content.contains("sk-proj-sensitive123456"))
            assertTrue(first.content.contains("[redacted]"))
            val working = report.getValue("workingTree").jsonObject
            assertEquals(true, working.getValue("dirty").jsonPrimitive.boolean)
            assertEquals(1, working.getValue("stagedCount").jsonPrimitive.int)
            assertEquals(1, working.getValue("unstagedCount").jsonPrimitive.int)
            assertEquals(1, working.getValue("untrackedCount").jsonPrimitive.int)

            val second = gateway.callTool(GIT_ACTIVITY_REPORT_TOOL, buildJsonObject {})
            assertEquals(first.content, second.content, "Fixed clock must make ordering and source IDs deterministic")

            val truncated = gateway.callTool(GIT_ACTIVITY_REPORT_TOOL, buildJsonObject {
                put("sinceHours", 24)
                put("maxCommits", 1)
                put("maxFiles", 1)
            })
            assertFalse(truncated.isError)
            assertEquals(true, Json.parseToJsonElement(truncated.content).jsonObject.getValue("truncated").jsonPrimitive.boolean)

            listOf(
                buildJsonObject { put("sinceHours", 0) },
                buildJsonObject { put("maxCommits", 51) },
                buildJsonObject { put("maxFiles", 101) },
                buildJsonObject { put("repositoryPath", repository.toString()) },
            ).forEach { arguments ->
                val invalid = gateway.callTool(GIT_ACTIVITY_REPORT_TOOL, arguments)
                assertTrue(invalid.isError)
                assertFalse(invalid.content.contains(repository.toString()))
            }

            git(repository, "checkout", "--detach", "HEAD")
            val detached = Json.parseToJsonElement(
                gateway.callTool(GIT_ACTIVITY_REPORT_TOOL, buildJsonObject {}).content,
            ).jsonObject
            assertEquals(true, detached.getValue("detached").jsonPrimitive.boolean)
            assertTrue(detached.getValue("branch") is kotlinx.serialization.json.JsonNull)
        } finally {
            gateway.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `empty repository and period without commits still report working tree safely`() {
        val root = Files.createTempDirectory("git-activity-empty")
        try {
            git(root, "init", "-b", "main")
            Files.writeString(root.resolve("untracked.txt"), "not returned as content")
            val empty = gitActivityReport(
                repositoryRoot = root,
                sinceHours = 1,
                clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC),
            )
            assertEquals(0, empty.getValue("commits").jsonArray.size)
            assertNull(empty.getValue("head").let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content })
            assertEquals("main", empty.getValue("branch").jsonPrimitive.content)
            assertEquals(true, empty.getValue("workingTree").jsonObject.getValue("dirty").jsonPrimitive.boolean)
            assertFalse(empty.toString().contains("not returned as content"))

            git(root, "config", "user.name", "Digest Test")
            git(root, "config", "user.email", "digest@example.invalid")
            git(root, "add", "untracked.txt")
            git(root, "commit", "-m", "Старый коммит", date = "2026-10-14T01:00:00Z")
            val noRecent = gitActivityReport(
                repositoryRoot = root,
                sinceHours = 1,
                clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC),
            )
            assertEquals(0, noRecent.getValue("commits").jsonArray.size)
            assertEquals(0, noRecent.getValue("files").jsonArray.size)
            assertEquals(false, noRecent.getValue("workingTree").jsonObject.getValue("dirty").jsonPrimitive.boolean)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun git(repository: Path, vararg arguments: String, date: String? = null) {
    val builder = ProcessBuilder(listOf("git") + arguments)
        .directory(repository.toFile())
        .redirectErrorStream(true)
    if (date != null) {
        builder.environment()["GIT_AUTHOR_DATE"] = date
        builder.environment()["GIT_COMMITTER_DATE"] = date
    }
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
}
