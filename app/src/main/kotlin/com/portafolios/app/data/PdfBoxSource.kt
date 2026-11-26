package com.portafolios.app.data

import com.portafolios.core.importer.Glyph
import com.portafolios.core.importer.PdfPasswordException
import com.portafolios.core.importer.PdfTextSource
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Positioned text from a PDF, read on the device with PdfBox-Android. Nothing leaves the phone. */
object PdfBoxSource : PdfTextSource {
    override fun glyphs(bytes: ByteArray): List<Glyph> {
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException()
        }
        doc.use {
            val out = mutableListOf<Glyph>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>) {
                    for (p in textPositions) {
                        out += Glyph(currentPageNo, p.xDirAdj, p.yDirAdj, p.widthDirAdj, p.heightDir, p.unicode ?: "")
                    }
                }
            }
            stripper.sortByPosition = true
            stripper.getText(it)
            return out
        }
    }
}
