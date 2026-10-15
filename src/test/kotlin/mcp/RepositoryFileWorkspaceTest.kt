package org.example.mcp

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepositoryFileWorkspaceTest {
    @Test
    fun `search and batch read are deterministic bounded and return sha256`() {
        withRepository { root, workspace ->
            write(root, "src/main/kotlin/demo/FixtureProjectApi.kt", "class FixtureProjectApi\nfun call() = FixtureProjectApi()\n")
            write(root, "frontend/src/api/fixtureProjectApi.ts", "export const FixtureProjectApi = 'FixtureProjectApi'\n")
            write(root, "docs/fixture-project-api.md", "# FixtureProjectApi\nOld documentation.\n")
            write(root, "build/generated.kt", "FixtureProjectApi must not be searched")

            val search = workspace.search("FixtureProjectApi")
            val matches = search.getValue("matches").jsonArray
            assertEquals(3, search.getValue("matchedFiles").jsonPrimitive.int)
            assertEquals(
                listOf(
                    "docs/fixture-project-api.md",
                    "frontend/src/api/fixtureProjectApi.ts",
                    "frontend/src/api/fixtureProjectApi.ts",
                    "src/main/kotlin/demo/FixtureProjectApi.kt",
                    "src/main/kotlin/demo/FixtureProjectApi.kt",
                ),
                matches.map { it.jsonObject.getValue("path").jsonPrimitive.content },
            )
            assertFalse(search.getValue("truncated").jsonPrimitive.boolean)
            assertTrue(matches.all { it.jsonObject.getValue("line").jsonPrimitive.int > 0 })
            assertTrue(matches.all { it.jsonObject.getValue("snippet").jsonPrimitive.content.length <= 241 })

            val read = workspace.readFiles(listOf(
                "src/main/kotlin/demo/FixtureProjectApi.kt",
                "docs/fixture-project-api.md",
                "frontend/src/api/fixtureProjectApi.ts",
            ))
            assertEquals(3, read.getValue("fileCount").jsonPrimitive.int)
            val files = read.getValue("files").jsonArray
            assertEquals(
                listOf("docs/fixture-project-api.md", "frontend/src/api/fixtureProjectApi.ts", "src/main/kotlin/demo/FixtureProjectApi.kt"),
                files.map { it.jsonObject.getValue("path").jsonPrimitive.content },
            )
            files.forEach { file ->
                val item = file.jsonObject
                assertEquals(
                    item.getValue("content").jsonPrimitive.content.toByteArray().size,
                    item.getValue("byteCount").jsonPrimitive.int,
                )
                assertTrue(item.getValue("sha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            }
        }
    }

    @Test
    fun `preview apply no-op create and stale hash preserve optimistic concurrency`() {
        withRepository { root, workspace ->
            val path = "docs/fixture-project-api.md"
            write(root, path, "# FixtureProjectApi\nOld documentation.\n")
            val oldHash = readHash(workspace, path)
            val replacement = "# FixtureProjectApi\nCurrent implementation uses GET /fixture.\n"

            val preview = workspace.writeFile(path, replacement, "preview", createOnly = false, expectedSha256 = oldHash)
            assertFalse(preview.getValue("applied").jsonPrimitive.boolean)
            assertFalse(preview.getValue("created").jsonPrimitive.boolean)
            assertContains(preview.getValue("diff").jsonPrimitive.content, "+Current implementation uses GET /fixture.")
            assertContains(Files.readString(root.resolve(path)), "Old documentation")

            val applied = workspace.writeFile(path, replacement, "apply", createOnly = false, expectedSha256 = oldHash)
            assertTrue(applied.getValue("applied").jsonPrimitive.boolean)
            assertFalse(applied.getValue("created").jsonPrimitive.boolean)
            assertEquals(replacement, Files.readString(root.resolve(path)))
            assertTrue(Files.list(root.resolve("docs")).use { stream ->
                stream.noneMatch { it.fileName.toString().startsWith(".project-write-") }
            })

            val newHash = applied.getValue("newSha256").jsonPrimitive.content
            val noOp = workspace.writeFile(path, replacement, "apply", createOnly = false, expectedSha256 = newHash)
            assertTrue(noOp.getValue("noOp").jsonPrimitive.boolean)
            assertFalse(noOp.getValue("applied").jsonPrimitive.boolean)
            assertFalse(noOp.getValue("diff").jsonPrimitive.content.lineSequence().any { it.startsWith("+") && !it.startsWith("+++") })
            assertFailsWith<IllegalArgumentException> {
                workspace.writeFile(path, "stale", "apply", createOnly = false, expectedSha256 = oldHash)
            }.also { assertContains(it.message.orEmpty(), "устарел") }

            val createPath = "docs/new-adr.md"
            val createPreview = workspace.writeFile(createPath, "# ADR\n", "preview", createOnly = true, expectedSha256 = null)
            assertTrue(createPreview.getValue("wouldCreate").jsonPrimitive.boolean)
            assertFalse(Files.exists(root.resolve(createPath)))
            val created = workspace.writeFile(createPath, "# ADR\n", "apply", createOnly = true, expectedSha256 = null)
            assertTrue(created.getValue("created").jsonPrimitive.boolean)
            assertEquals("# ADR\n", Files.readString(root.resolve(createPath)))
            assertFailsWith<IllegalArgumentException> {
                workspace.writeFile(createPath, "overwrite", "apply", createOnly = true, expectedSha256 = null)
            }
        }
    }

    @Test
    fun `policy rejects absolute traversal symlinks excluded paths secrets binary and protected writes`() {
        withRepository { root, workspace ->
            write(root, "docs/allowed.md", "allowed")
            write(root, ".env", "secret=value")
            write(root, ".llm-task-state.json", "{}")
            write(root, "video/demo.md", "hidden")
            write(root, "AGENTS.md", "protected")
            write(root, "docs/binary.md", byteArrayOf(0, 1, 2))
            val outside = Files.createTempFile("project-workspace-outside", ".md")
            Files.writeString(outside, "outside secret")
            Files.createSymbolicLink(root.resolve("docs/linked.md"), outside)
            Files.createDirectories(root.resolve("linked-parent-target"))
            Files.writeString(root.resolve("linked-parent-target/file.md"), "linked parent")
            Files.createSymbolicLink(root.resolve("docs/link-dir"), root.resolve("linked-parent-target"))

            fun rejected(vararg paths: String) {
                val error = assertFailsWith<IllegalArgumentException> { workspace.readFiles(paths.toList()) }
                assertFalse(error.message.orEmpty().contains(root.toString()))
                assertFalse(error.message.orEmpty().contains(outside.toString()))
            }
            rejected(outside.toString())
            rejected("C:/secret.md")
            rejected("../outside.md")
            rejected(".env")
            rejected(".llm-task-state.json")
            rejected("video/demo.md")
            rejected("docs/linked.md")
            rejected("docs/link-dir/file.md")
            rejected("docs/binary.md")
            assertFailsWith<IllegalArgumentException> {
                workspace.writeFile("AGENTS.md", "changed", "apply", createOnly = false, expectedSha256 = "0".repeat(64))
            }
            assertFailsWith<IllegalArgumentException> {
                workspace.writeFile("frontend/package.json", "{}", "apply", createOnly = true, expectedSha256 = null)
            }
            assertEquals("protected", Files.readString(root.resolve("AGENTS.md")))
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `count and size limits fail closed or mark search truncation`() {
        withRepository { root, workspace ->
            write(root, "docs/many.md", (1..60).joinToString("\n") { "needle $it" })
            val search = workspace.search("needle")
            assertEquals(60, search.getValue("totalMatches").jsonPrimitive.int)
            assertEquals(50, search.getValue("returnedMatches").jsonPrimitive.int)
            assertTrue(search.getValue("truncated").jsonPrimitive.boolean)
            assertFailsWith<IllegalArgumentException> { workspace.search("x".repeat(201)) }

            (1..6).forEach { write(root, "docs/$it.md", "$it") }
            assertFailsWith<IllegalArgumentException> {
                workspace.readFiles((1..6).map { "docs/$it.md" })
            }
            write(root, "docs/large.md", "x".repeat(256 * 1024 + 1))
            assertFailsWith<IllegalArgumentException> { workspace.readFiles(listOf("docs/large.md")) }
            assertFailsWith<IllegalArgumentException> {
                workspace.writeFile("docs/new.md", "x".repeat(256 * 1024 + 1), "apply", true, null)
            }
        }
    }

    private fun withRepository(block: (Path, RepositoryFileWorkspace) -> Unit) {
        val root = Files.createTempDirectory("project-file-workspace-test")
        try {
            block(root, RepositoryFileWorkspace(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(root: Path, relative: String, content: String) = write(root, relative, content.toByteArray())

    private fun write(root: Path, relative: String, content: ByteArray) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, content)
    }

    private fun readHash(workspace: RepositoryFileWorkspace, path: String): String = workspace.readFiles(listOf(path))
        .getValue("files").jsonArray.single().jsonObject.getValue("sha256").jsonPrimitive.content
}
