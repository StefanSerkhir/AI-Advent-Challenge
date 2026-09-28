package org.example.indexing

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.*

class PdfTextExtractorTest {
    @Test
    fun `extracts multiple pages and structured chunking preserves page section`() {
        val directory = createTempDirectory("pdf-extractor")
        val file = directory.resolve("manual.pdf")
        try {
            writeTestPdf(file, listOf("First page installation guide", "Second page troubleshooting"))

            val text = PdfTextExtractor().extract(file, "docs/manual.pdf")
            assertContains(text, "# PDF page 1\n\nFirst page installation guide")
            assertContains(text, "# PDF page 2\n\nSecond page troubleshooting")

            val document = NormalizedDocument(
                source = "docs/manual.pdf",
                title = "manual.pdf",
                kind = DocumentKind.PDF,
                text = text,
                contentHash = sha256(text),
            )
            val chunks = StructureAwareChunkingStrategy(targetSize = 300).chunk(listOf(document))
            assertEquals(listOf("PDF page 1", "PDF page 2"), chunks.map(ChunkDraft::section))
            assertTrue(chunks.all { it.source == "docs/manual.pdf" && it.text.isNotBlank() })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects encrypted empty corrupt oversized and excessive page PDFs`() {
        val directory = createTempDirectory("pdf-extractor-errors")
        try {
            val encrypted = directory.resolve("encrypted.pdf")
            writeTestPdf(encrypted, listOf("secret text"), password = "reader-password")
            assertContains(
                assertFailsWith<PdfExtractionException> {
                    PdfTextExtractor().extract(encrypted, "docs/encrypted.pdf")
                }.message.orEmpty(),
                "паролем",
            )

            val empty = directory.resolve("empty.pdf")
            writeTestPdf(empty, listOf(null))
            assertContains(
                assertFailsWith<PdfExtractionException> {
                    PdfTextExtractor().extract(empty, "docs/empty.pdf")
                }.message.orEmpty(),
                "OCR",
            )

            val corrupt = directory.resolve("corrupt.pdf")
            corrupt.writeBytes("%PDF-not-a-valid-document".encodeToByteArray())
            assertFailsWith<PdfExtractionException> {
                PdfTextExtractor().extract(corrupt, "docs/corrupt.pdf")
            }

            val twoPages = directory.resolve("two-pages.pdf")
            writeTestPdf(twoPages, listOf("one", "two"))
            assertContains(
                assertFailsWith<PdfExtractionException> {
                    PdfTextExtractor(maxPages = 1).extract(twoPages, "docs/two-pages.pdf")
                }.message.orEmpty(),
                "лимит 1",
            )
            assertContains(
                assertFailsWith<PdfExtractionException> {
                    PdfTextExtractor(maxFileBytes = 10).extract(twoPages, "docs/two-pages.pdf")
                }.message.orEmpty(),
                "10 байт",
            )
            assertContains(
                assertFailsWith<PdfExtractionException> {
                    PdfTextExtractor(maxExtractedCharacters = 20).extract(twoPages, "docs/two-pages.pdf")
                }.message.orEmpty(),
                "20 символов",
            )
            assertFailsWith<PdfExtractionException> {
                PdfTextExtractor(maxExtractedCharacters = 35).extract(twoPages, "docs/two-pages.pdf")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
