package org.example.indexing

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Path

internal fun writeTestPdf(
    file: Path,
    pages: List<String?>,
    password: String? = null,
) {
    PDDocument().use { document ->
        pages.forEach { text ->
            val page = PDPage()
            document.addPage(page)
            if (text != null) {
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(72f, 720f)
                    content.showText(text)
                    content.endText()
                }
            }
        }
        if (password != null) {
            document.protect(
                StandardProtectionPolicy("owner-password", password, AccessPermission()).apply {
                    encryptionKeyLength = 128
                },
            )
        }
        document.save(file.toFile())
    }
}
