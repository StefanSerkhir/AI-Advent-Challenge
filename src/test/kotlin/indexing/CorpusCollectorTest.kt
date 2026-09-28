package org.example.indexing

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.*

class CorpusCollectorTest {
    @Test
    fun `walk is deterministic and excludes secrets generated files and unsupported roots`() {
        val root = createTempDirectory("index-corpus")
        try {
            root.resolve("README.md").writeText("\uFEFF# Readme\r\ntext   \r\n")
            root.resolve("docs/z.md").also { it.parent.createDirectories(); it.writeText("# Z") }
            root.resolve("docs/a.md").writeText("# A")
            root.resolve("src/main/kotlin/B.kt").also { it.parent.createDirectories(); it.writeText("class B") }
            root.resolve("frontend/src/a.tsx").also { it.parent.createDirectories(); it.writeText("export const A = 1") }
            root.resolve(".env").writeText("openai_api_key=must-not-appear")
            root.resolve(".llm-secret.md").writeText("secret")
            root.resolve("build/generated.kt").also { it.parent.createDirectories(); it.writeText("class Generated") }
            root.resolve("src/test/kotlin/Test.kt").also { it.parent.createDirectories(); it.writeText("class Test") }
            root.resolve("src/main/kotlin/generated/Generated.kt").also { it.parent.createDirectories(); it.writeText("class Generated") }
            root.resolve("legacy-desktop/src/main/kotlin/Old.kt").also { it.parent.createDirectories(); it.writeText("class Old") }

            val corpus = RepositoryCorpusCollector(minimumPages = 0.0).collect(root)
            val sources = corpus.documents.map(NormalizedDocument::source)

            assertEquals(sources.sorted(), sources)
            assertEquals(listOf("README.md", "docs/a.md", "docs/z.md", "frontend/src/a.tsx", "src/main/kotlin/B.kt"), sources)
            assertFalse(corpus.documents.any { "must-not-appear" in it.text })
            assertEquals("# Readme\ntext", corpus.documents.first().text)
            assertEquals(sha256(corpus.manifest.documents.joinToString("\n") { "${it.source}:${it.contentHash}" }), corpus.manifest.manifestHash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `undersized corpus fails before an index can be produced`() {
        val root = createTempDirectory("small-index-corpus")
        try {
            root.resolve("README.md").writeText("tiny")
            val error = assertFailsWith<CorpusTooSmallException> {
                RepositoryCorpusCollector(minimumPages = 20.0).collect(root)
            }
            assertTrue(error.message.orEmpty().contains("Индекс не создан"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
