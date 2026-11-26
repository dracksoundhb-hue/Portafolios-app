package com.portafolios.core

import com.portafolios.core.importer.Glyph
import com.portafolios.core.importer.PdfPasswordException
import com.portafolios.core.importer.PdfTextSource
import com.portafolios.core.importer.StatementFile
import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same extraction as the Android app (PdfBox-Android mirrors this PDFBox 2.0 API). */
object TestPdfSource : PdfTextSource {
    override fun glyphs(bytes: ByteArray): List<Glyph> {
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw PdfPasswordException()
        }
        doc.use {
            val out = mutableListOf<Glyph>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<TextPosition>) {
                    positions.forEach { p -> out += Glyph(currentPageNo, p.xDirAdj, p.yDirAdj, p.widthDirAdj, p.heightDir, p.unicode ?: "") }
                }
            }
            stripper.sortByPosition = true
            stripper.getText(it)
            return out
        }
    }
}

class PdfTest {
    /** Writes lines; each line is a list of (x, text) cells. */
    private fun pdf(lines: List<List<Pair<Float, String>>>, password: String? = null): ByteArray {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                var y = 750f
                for (line in lines) {
                    for ((x, text) in line) {
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 6f); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
                    }
                    y -= 14f
                }
            }
            if (password != null) {
                doc.protect(StandardProtectionPolicy("owner-$password", password, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            val out = ByteArrayOutputStream(); doc.save(out); return out.toByteArray()
        }
    }

    private fun file(bytes: ByteArray) = StatementFile("extracto.pdf", bytes, TestPdfSource)

    @Test fun tabularPdfUsesBrokerParser() {
        val cols = listOf(20f, 90f, 170f, 210f, 280f, 340f, 400f, 500f)
        fun row(vararg v: String) = cols.zip(v.toList())
        val bytes = pdf(listOf(
            listOf(40f to "Trading 212 - Account statement"),
            row("Action", "Time", "Ticker", "Name", "No. of shares", "Price / share", "Currency (Price / share)", "Total"),
            row("Market buy", "2024-01-15 14:30:00", "AAPL", "Apple Inc", "2", "185.50", "USD", "341.12"),
            row("Market sell", "2024-02-15 14:30:00", "AAPL", "Apple Inc", "1", "190.00", "USD", "175.50"),
        ))
        val p = preview(file(bytes), Broker.TRADING_212)
        assertNull(p.error, p.error)
        assertEquals(listOf(TxType.BUY, TxType.SELL), p.rows.map { it.transaction.type })
        assertEquals(BigDecimal("185.50"), p.rows[0].transaction.price)
    }

    @Test fun quantfuryStyleLines() {
        val bytes = pdf(listOf(
            listOf(40f to "Quantfury - Trading history"),
            listOf(40f to "2024-01-15 14:30:12", 160f to "Open Long", 240f to "AAPL", 300f to "10", 360f to "185.50", 440f to "1,855.00", 520f to "USD"),
            listOf(40f to "2024-02-01 10:00:00", 160f to "Close Long", 240f to "AAPL", 300f to "4", 360f to "190.25", 440f to "761.00", 520f to "USD"),
            listOf(40f to "2024-02-03 09:12:00", 160f to "Open Long", 240f to "BTC/USDT", 300f to "0.05", 360f to "42,000.00", 440f to "2,100.00"),
            listOf(40f to "2024-02-05 09:00:00", 160f to "Deposit", 300f to "5,000.00", 440f to "USD"),
            listOf(40f to "Order 123456789", 160f to "Balance", 440f to "4,000.00"),
        ))
        val p = preview(file(bytes), Broker.QUANTFURY)
        assertNull(p.error, p.error + p.issues)
        val t = p.rows.map { it.transaction }
        assertEquals(listOf(TxType.BUY, TxType.SELL, TxType.BUY, TxType.DEPOSIT), t.map { it.type })
        assertEquals(BigDecimal("10"), t[0].quantity); assertEquals(BigDecimal("185.50"), t[0].price)
        assertEquals("BTC", t[2].symbol); assertEquals(BigDecimal("0.05"), t[2].quantity)
        assertEquals(BigDecimal("5000.00"), t[3].amount)
        assertEquals(LocalDate.of(2024, 2, 1), t[1].date)
    }

    @Test fun tradeConfirmationWithIsinAndFee() {
        val bytes = pdf(listOf(
            listOf(40f to "Trade confirmation"),
            listOf(40f to "15/01/2024", 120f to "Compra", 200f to "IE00B5BMR087", 320f to "iShares Core S&P 500", 460f to "10", 500f to "450,20", 560f to "4.504,00"),
        ))
        val p = preview(file(bytes), Broker.DEGIRO)
        assertNull(p.error, p.error + p.issues)
        val t = p.rows.single().transaction
        assertEquals(TxType.BUY, t.type); assertEquals("IE00B5BMR087", t.instrumentKey)
        assertEquals(BigDecimal("450.20"), t.price); assertEquals(BigDecimal("2.00"), t.fee)
    }

    @Test fun ambiguousLineIsReportedNotGuessed() {
        val bytes = pdf(listOf(listOf(40f to "2024-01-15", 160f to "Buy", 240f to "MSFT", 300f to "10", 360f to "7")))
        val p = preview(file(bytes), Broker.QUANTFURY)
        assertNotNull(p.error)
        assertTrue(p.issues.single().message.contains("cantidad y precio"))
    }

    @Test fun passwordProtectedPdf() {
        val p = preview(file(pdf(listOf(listOf(40f to "x")), password = "1234")), Broker.QUANTFURY)
        assertTrue(p.error!!.contains("contraseña"))
    }

    @Test fun pdfWithoutTextIsExplained() {
        val p = preview(file(pdf(emptyList())), Broker.QUANTFURY)
        assertTrue(p.error!!.contains("imagen"))
    }

    @Test fun quantfuryTradingHistoryReport() {
        val x = listOf(20f, 70f, 180f, 260f, 320f, 400f, 480f)
        fun row(vararg v: String) = x.zip(v.toList()).filter { it.second.isNotEmpty() }
        val bytes = pdf(listOf(
            listOf(300f to "Informe del historial de trading"),
            listOf(40f to "Información de la cuenta"),
            listOf(40f to "Nombre del cliente: CLIENTE DE PRUEBA"),
            listOf(40f to "Posiciones Abiertas"),
            listOf(40f to "Acciones y ETFs"),
            row("Símbolo", "Fecha/Hora de la posición", "Acción", "Precio", "Cantidad", "Valor", "G&P realizadas de la"),
            listOf(560f to "posición"),
            row("NKE", "17/08/2026 1:47:47 PM UTC", "COMPRAR (abrir)", "$39.99", "4.0010 acciones", "$160.00", ""),
            row("NKE", "17/08/2026 2:07:35 PM UTC", "COMPRAR (añadir)", "$39.63", "6.0560 acciones", "$240.00", ""),
            row("NKE", "02/10/2026 1:30:04 PM UTC", "VENDER (reducir)", "$32.43", "5.0285 acciones", "$200.00", "-$36.93"),
            listOf(40f to "Pares de criptomonedas"),
            row("ETH/USD", "07/11/2026 7:49:33 AM UTC", "COMPRAR (abrir)", "2,242.17", "0.99992573 ETH", "$2,242.00", ""),
            listOf(40f to "Posiciones Cerradas"),
            row("TSLA", "03/07/2025 2:39:31 PM UTC", "VENDER (abrir)", "$300.00", "2.0000 acciones", "$600.00", ""),
            row("TSLA", "09/07/2025 1:57:13 PM UTC", "COMPRAR (cerrar)", "$280.00", "2.0000 acciones", "$600.00", "+$40.00"),
            row("Total", "", "", "", "", "", "+$40.00"),
            listOf(40f to "Informe del historial de trading: 2 jul 2025 – 26 nov 2026   |   Página: 1"),
        ))
        val p = preview(file(bytes), Broker.QUANTFURY)
        assertNull(p.error, p.error + p.issues)
        assertTrue(p.issues.isEmpty(), p.issues.toString())
        val t = p.rows.map { it.transaction }
        assertEquals(listOf(TxType.BUY, TxType.BUY, TxType.SELL, TxType.BUY, TxType.SHORT, TxType.COVER), t.map { it.type })
        assertEquals(LocalDate.of(2026, 10, 2), t[2].date)
        assertEquals(BigDecimal("5.0285"), t[2].quantity)
        assertEquals("ETH/USD", t[3].symbol); assertEquals(BigDecimal("2242.17"), t[3].price)

        val positions = com.portafolios.core.calc.PortfolioCalculator.positions(t).associateBy { it.symbol }
        assertEquals(0, BigDecimal("-36.93").compareTo(positions.getValue("NKE").realizedPnl)) // same as Quantfury's G&P
        assertEquals(0, BigDecimal("40.00").compareTo(positions.getValue("TSLA").realizedPnl))
        assertEquals(0, BigDecimal.ZERO.compareTo(positions.getValue("TSLA").quantity))
    }
}
