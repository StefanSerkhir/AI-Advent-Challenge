package org.example.indexing

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

const val DEFAULT_MAX_PDF_BYTES = 50L * 1024 * 1024
const val DEFAULT_MAX_PDF_PAGES = 1_000
const val DEFAULT_MAX_EXTRACTED_PDF_CHARACTERS = 10_000_000

class PdfExtractionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class PdfTextExtractor(
    private val maxFileBytes: Long = DEFAULT_MAX_PDF_BYTES,
    private val maxPages: Int = DEFAULT_MAX_PDF_PAGES,
    private val maxExtractedCharacters: Int = DEFAULT_MAX_EXTRACTED_PDF_CHARACTERS,
) {
    init {
        require(maxFileBytes > 0) { "PDF file limit must be positive" }
        require(maxPages > 0) { "PDF page limit must be positive" }
        require(maxExtractedCharacters > 0) { "PDF text limit must be positive" }
    }

    fun extract(file: Path, source: String): String {
        val fileBytes = try {
            Files.size(file)
        } catch (error: IOException) {
            throw PdfExtractionException("Не удалось прочитать PDF: $source", error)
        }
        if (fileBytes > maxFileBytes) {
            throw PdfExtractionException("PDF превышает лимит $maxFileBytes байт: $source")
        }
        try {
            Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { document ->
                if (document.isEncrypted) {
                    throw PdfExtractionException("Зашифрованный PDF не поддерживается: $source")
                }
                if (document.numberOfPages > maxPages) {
                    throw PdfExtractionException("PDF содержит ${document.numberOfPages} страниц; лимит $maxPages: $source")
                }
                val extractedPages = mutableListOf<String>()
                var extractedCharacters = 0
                for (pageNumber in 1..document.numberOfPages) {
                    val marker = "# PDF page $pageNumber\n\n"
                    val separatorLength = if (extractedPages.isEmpty()) 0 else 2
                    val remainingCharacters = maxExtractedCharacters - extractedCharacters - separatorLength - marker.length
                    if (remainingCharacters <= 0) throw extractedTextLimit(source)
                    val writer = BoundedTextWriter(remainingCharacters)
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = pageNumber
                        endPage = pageNumber
                    }
                    try {
                        stripper.writeText(document, writer)
                    } catch (_: PdfTextLimitExceededException) {
                        throw extractedTextLimit(source)
                    }
                    val pageText = normalizeText(writer.toString())
                    if (pageText.isBlank()) continue
                    val markedPage = marker + pageText
                    extractedCharacters += separatorLength + markedPage.length
                    extractedPages += markedPage
                }
                if (extractedPages.isEmpty()) {
                    throw PdfExtractionException(
                        "PDF не содержит извлекаемого текста: $source. Для сканированного документа сначала выполните OCR.",
                    )
                }
                return extractedPages.joinToString("\n\n")
            }
        } catch (error: PdfExtractionException) {
            throw error
        } catch (error: InvalidPasswordException) {
            throw PdfExtractionException("PDF защищён паролем и не может быть проиндексирован: $source", error)
        } catch (error: IOException) {
            throw PdfExtractionException("Не удалось безопасно извлечь текст из PDF: $source", error)
        } catch (error: RuntimeException) {
            throw PdfExtractionException("PDF повреждён или использует неподдерживаемую структуру: $source", error)
        }
    }

    private fun extractedTextLimit(source: String) = PdfExtractionException(
        "Извлечённый текст PDF превышает лимит $maxExtractedCharacters символов: $source",
    )

    private class PdfTextLimitExceededException : IOException()

    private class BoundedTextWriter(private val limit: Int) : Writer() {
        private val value = StringBuilder(minOf(limit, 16_384))

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (value.length + length > limit) throw PdfTextLimitExceededException()
            value.append(buffer, offset, length)
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = value.toString()
    }
}
