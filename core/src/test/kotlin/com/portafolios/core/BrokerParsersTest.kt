package com.portafolios.core

import com.portafolios.core.model.Broker
import com.portafolios.core.model.TxType
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerParsersTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test fun trading212() {
        val p = preview(csv("t212.csv", """
            Action,Time,ISIN,Ticker,Name,No. of shares,Price / share,Currency (Price / share),Exchange rate,Result,Currency (Result),Total,Currency (Total),Currency conversion fee,Currency (Currency conversion fee),ID
            Deposit,2024-01-10 09:00:00,,,,,,,,,,1000.00,EUR,,,DEP1
            Market buy,2024-01-15 14:30:00,US0378331005,AAPL,Apple,2,185.50,USD,1.09,,,341.12,EUR,0.51,EUR,EOF1
            Market sell,2024-02-15 14:30:00,US0378331005,AAPL,Apple,1,190.00,USD,1.08,4.10,EUR,175.50,EUR,0.26,EUR,EOF2
            Dividend (Ordinary),2024-02-20 10:00:00,US0378331005,AAPL,Apple,1,0.24,USD,,,,0.19,EUR,,,
        """), Broker.TRADING_212)
        assertNull(p.error)
        assertEquals(listOf(TxType.DEPOSIT, TxType.BUY, TxType.SELL, TxType.DIVIDEND), p.rows.map { it.transaction.type })
        val buy = p.rows[1].transaction
        assertEquals("AAPL", buy.symbol); assertEquals(bd("2"), buy.quantity); assertEquals(bd("0.51"), buy.fee)
        assertEquals(LocalDate.of(2024, 1, 15), buy.date)
    }

    @Test fun degiroSpanish() {
        val p = preview(csv("Transactions.csv", """
            Fecha,Hora,Producto,ISIN,Bolsa de referencia,Centro de ejecución,Número,Precio,,Valor local,,Valor,,Tipo de cambio,Costes de transacción,,Total,,ID Orden
            15-01-2024,09:05,ISHARES CORE S&P 500,IE00B5BMR087,EAM,XAMS,10,"450,20",EUR,"-4502,00",EUR,"-4502,00",EUR,,"-2,00",EUR,"-4504,00",EUR,abc-1
            20-03-2024,10:00,ISHARES CORE S&P 500,IE00B5BMR087,EAM,XAMS,-4,"480,00",EUR,"1920,00",EUR,"1920,00",EUR,,"-2,00",EUR,"1918,00",EUR,abc-2
        """), Broker.DEGIRO)
        assertNull(p.error)
        val (b, s) = p.rows.map { it.transaction }
        assertEquals(TxType.BUY, b.type); assertEquals(bd("450.20"), b.price); assertEquals("IE00B5BMR087", b.instrumentKey)
        assertEquals(bd("2.00"), b.fee); assertEquals("EUR", b.currency)
        assertEquals(TxType.SELL, s.type); assertEquals(bd("4"), s.quantity)
    }

    @Test fun revolut() {
        val p = preview(csv("revolut.csv", """
            Date,Ticker,Type,Quantity,Price per share,Total Amount,Currency,FX Rate
            2024-01-10T10:00:00.000Z,,CASH TOP-UP,,,USD 500,USD,1.09
            2024-01-15T14:30:00.123Z,TSLA,BUY - MARKET,2,USD 210.00,USD 421.00,USD,1.09
            2024-02-01T09:00:00.000Z,TSLA,SELL - LIMIT,1,USD 220.00,USD 219.00,USD,1.09
            2024-03-01T09:00:00.000Z,,CUSTODY FEE,,,USD -1.20,USD,1.09
        """), Broker.REVOLUT)
        assertNull(p.error)
        assertEquals(listOf(TxType.DEPOSIT, TxType.BUY, TxType.SELL, TxType.FEE), p.rows.map { it.transaction.type })
        assertEquals(bd("1.00"), p.rows[1].transaction.fee)
        assertEquals(bd("1.20"), p.rows[3].transaction.amount)
    }

    @Test fun interactiveBrokers() {
        val p = preview(csv("U123.csv", """
            Statement,Header,Field Name,Field Value
            Statement,Data,BrokerName,Interactive Brokers
            Trades,Header,DataDiscriminator,Asset Category,Currency,Symbol,Date/Time,Quantity,T. Price,C. Price,Proceeds,Comm/Fee,Basis,Realized P/L,MTM P/L,Code
            Trades,Data,Order,Stocks,USD,MSFT,"2024-01-15, 10:30:00",5,400.10,401,-2000.5,-1,2001.5,0,4.5,O
            Trades,Data,Order,Stocks,USD,MSFT,"2024-02-15, 10:30:00",-2,420,421,840,-1,-800.4,38.6,2,C
            Trades,SubTotal,,Stocks,USD,MSFT,,3,,,,,,,,
            Trades,Data,Order,Forex,EUR,EUR.USD,"2024-01-14, 10:30:00",1000,1.09,1.09,-1090,-2,0,0,0,
            Dividends,Header,Currency,Date,Description,Amount
            Dividends,Data,USD,2024-03-14,MSFT(US5949181045) Cash Dividend USD 0.75 per Share (Ordinary Dividend),2.25
            Dividends,Data,Total,,,2.25
            Withholding Tax,Header,Currency,Date,Description,Amount,Code
            Withholding Tax,Data,USD,2024-03-14,MSFT(US5949181045) Cash Dividend USD 0.75 per Share - US Tax,-0.34,
            Deposits & Withdrawals,Header,Currency,Settle Date,Description,Amount
            Deposits & Withdrawals,Data,USD,2024-01-02,Electronic Fund Transfer,5000
        """), Broker.INTERACTIVE_BROKERS)
        assertNull(p.error)
        assertEquals(listOf(TxType.BUY, TxType.SELL, TxType.DIVIDEND, TxType.TAX, TxType.DEPOSIT), p.rows.map { it.transaction.type })
        assertEquals("MSFT", p.rows[2].transaction.symbol)
        assertEquals(bd("1"), p.rows[0].transaction.fee)
    }

    @Test fun schwab() {
        val p = preview(csv("schwab.csv", """
            "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
            "01/15/2024","Buy","VOO","VANGUARD S&P 500 ETF","3","$430.00","","-$1,290.00"
            "02/01/2024 as of 01/31/2024","Qualified Dividend","VOO","VANGUARD S&P 500 ETF","","","","$4.50"
            "02/05/2024","MoneyLink Transfer","","Tfr BANK","","","","$2,000.00"
            "Transactions Total","","","","","","","$714.50"
        """), Broker.CHARLES_SCHWAB)
        assertNull(p.error)
        assertEquals(listOf(TxType.BUY, TxType.DIVIDEND, TxType.DEPOSIT), p.rows.map { it.transaction.type })
        assertEquals(LocalDate.of(2024, 2, 1), p.rows[1].transaction.date)
        assertEquals(bd("1290.00"), p.rows[0].transaction.amount)
    }

    @Test fun robinhoodAndCrypto() {
        val text = """
            "Activity Date","Process Date","Settle Date","Instrument","Description","Trans Code","Quantity","Price","Amount"
            "1/15/2024","1/15/2024","1/17/2024","AMD","Advanced Micro Devices","Buy","4","$150.00","($600.00)"
            "2/1/2024","2/1/2024","2/1/2024","","ACH Deposit","ACH","","","$1,000.00"
            "3/1/2024","3/1/2024","3/1/2024","AMD","Cash Div","CDIV","","","$1.20"
            "","","","","","","","",""
            "","","","","The data provided is for informational purposes only.","","","",""
        """
        val p = preview(csv("rh.csv", text), Broker.ROBINHOOD)
        assertEquals(listOf(TxType.BUY, TxType.DEPOSIT, TxType.DIVIDEND), p.rows.map { it.transaction.type })
        assertEquals(bd("600.00"), p.rows[0].transaction.amount)
        assertNull(preview(csv("rh.csv", text), Broker.ROBINHOOD_CRYPTO).error)
    }

    @Test fun etrade() {
        val p = preview(csv("etrade.csv", """
            For Account:,####1234
            TransactionDate,TransactionType,SecurityType,Symbol,Quantity,Amount,Price,Commission,Description
            01/15/24,Bought,EQ,NVDA,10,-5000.00,500.00,0,NVIDIA CORP
            03/10/24,Sold,EQ,NVDA,-5,4500.00,900.00,0,NVIDIA CORP
            03/28/24,Dividend,EQ,NVDA,0,0.20,0,0,NVIDIA CORP
        """), Broker.ETRADE)
        assertNull(p.error)
        assertEquals(listOf(TxType.BUY, TxType.SELL, TxType.DIVIDEND), p.rows.map { it.transaction.type })
        assertEquals(bd("5"), p.rows[1].transaction.quantity)
    }

    @Test fun webull() {
        val p = preview(csv("webull.csv", """
            Name,Symbol,Side,Status,Filled,Total Qty,Price,Avg Price,Time-in-Force,Placed Time,Filled Time
            Apple Inc,AAPL,Buy,Filled,3,3,@185,185.10,DAY,01/15/2024 09:31:00 EST,01/15/2024 09:31:02 EST
            Apple Inc,AAPL,Sell,Cancelled,0,3,@200,,GTC,01/20/2024 09:31:00 EST,
        """), Broker.WEBULL)
        assertEquals(1, p.rows.size); assertEquals(1, p.skipped)
        assertEquals(bd("185.10"), p.rows[0].transaction.price)
    }

    @Test fun tastytrade() {
        val p = preview(csv("tasty.csv", """
            Date,Type,Sub Type,Action,Symbol,Instrument Type,Description,Value,Quantity,Average Price,Commissions,Fees,Multiplier,Root Symbol,Underlying Symbol,Expiration Date,Strike Price,Call or Put,Order #,Currency
            2024-01-15T10:30:00-0500,Trade,Buy to Open,BUY_TO_OPEN,SPY,Equity,Bought 2 SPY @ 470.00,-940.00,2,-470.00,0.00,-0.02,1,SPY,SPY,,,,1001,USD
            2024-01-16T10:30:00-0500,Trade,Sell to Open,SELL_TO_OPEN,SPY 240216P00450000,Equity Option,Sold 1 SPY put,120.00,1,120.00,-1.00,-0.14,100,SPY,SPY,2/16/24,450,PUT,1002,USD
            2024-01-02T09:00:00-0500,Money Movement,Deposit,,,,ACH DEPOSIT,2000.00,0,,0.00,0.00,,,,,,,,USD
        """), Broker.TASTYTRADE)
        assertNull(p.error)
        assertEquals(listOf(TxType.BUY, TxType.DEPOSIT), p.rows.map { it.transaction.type })
        assertEquals(bd("0.02"), p.rows[0].transaction.fee)
    }

    @Test fun snowballAndYahoo() {
        val s = preview(csv("snowball.csv", """
            Event,Date,Symbol,Price,Quantity,Currency,FeeTax,Exchange,NKD,FeeCurrency,DoNotAdjustCash,Note
            Cash_In,2024-01-02,,1000,1,USD,0,,,,,
            Buy,2024-01-15,KO,60.10,10,USD,1,NYSE,,USD,,
            Dividend,2024-04-01,KO,0.485,10,USD,0,,,,,
        """), Broker.SNOWBALL_ANALYTICS)
        assertEquals(listOf(TxType.DEPOSIT, TxType.BUY, TxType.DIVIDEND), s.rows.map { it.transaction.type })
        assertEquals(bd("4.850"), s.rows[2].transaction.amount)
        val y = preview(csv("quotes.csv", """
            Symbol,Current Price,Date,Time,Change,Open,High,Low,Volume,Trade Date,Purchase Price,Quantity,Commission,High Limit,Low Limit,Comment
            GOOGL,170.1,2024/11/20,16:00 EST,1.2,169,171,168,100000,20240115,140.5,5,0,,,
            MSFT,415,2024/11/20,16:00 EST,1.2,414,416,413,100000,,,,,,,
        """), Broker.YAHOO_FINANCE)
        assertEquals(1, y.rows.size); assertEquals(LocalDate.of(2024, 1, 15), y.rows[0].transaction.date)
    }

    @Test fun xtbExcel() {
        val p = preview(xlsx("CASH OPERATION HISTORY", listOf(
            listOf("Name and surname", "Account", "Currency"),
            listOf("Leo", "#123456", "EUR"),
            listOf("ID", "Type", "Time", "Comment", "Symbol", "Amount"),
            listOf("#1", "deposit", "02.01.2024 10:00:00", "Deposit", "", "#1000"),
            listOf("#2", "Stocks/ETF purchase", "15.01.2024 15:30:00", "OPEN BUY 2 @ 185.50", "AAPL.US", "#-371"),
            listOf("#3", "Stocks/ETF sale", "15.02.2024 15:30:00", "CLOSE BUY 1/2 @ 190.00", "AAPL.US", "#190"),
            listOf("#4", "DIVIDENT", "20.02.2024 10:00:00", "AAPL.US USD 0.2400/ SHR", "AAPL.US", "#0.22"),
            listOf("", "Total", "", "", "", "#819.22"),
        )), Broker.XTB)
        assertNull(p.error, p.error)
        assertEquals(listOf(TxType.DEPOSIT, TxType.BUY, TxType.SELL, TxType.DIVIDEND), p.rows.map { it.transaction.type })
        assertEquals("AAPL", p.rows[1].transaction.symbol)
        assertEquals(bd("185.50"), p.rows[1].transaction.price)
    }

    @Test fun etoroExcel() {
        val p = preview(xlsx("Account Activity", listOf(
            listOf("Date", "Type", "Details", "Amount", "Units", "Realized Equity Change", "Realized Equity", "Balance", "Position ID", "Asset type", "Non Withdrawable Amount"),
            listOf("02/01/2024 10:00:00", "Deposit", "USD", "#1000", "-", "#1000", "#1000", "#1000", "", "", "#0"),
            listOf("15/01/2024 15:30:00", "Open Position", "NVDA/USD", "#500", "#1", "#0", "#1000", "#500", "2001", "Stocks", "#0"),
            listOf("10/03/2024 15:30:00", "Position closed", "NVDA/USD", "#880", "#1", "#380", "#1380", "#1380", "2001", "Stocks", "#0"),
        )), Broker.ETORO)
        assertNull(p.error, p.error)
        assertEquals(listOf(TxType.DEPOSIT, TxType.BUY, TxType.SELL), p.rows.map { it.transaction.type })
        assertEquals(bd("500.00000000"), p.rows[1].transaction.price)
    }

    @Test fun genericForQuantfuryApexAndOther() {
        val file = csv("export.csv", """
            Fecha;Símbolo;Tipo;Cantidad;Precio;Comisión;Divisa
            15/01/2024;AMZN;Compra;3;155,20;1,00;USD
            20/02/2024;AMZN;Venta;1;170,00;1,00;USD
        """)
        for (b in listOf(Broker.QUANTFURY, Broker.APEX_CLEARING, Broker.OTHER)) {
            val p = preview(file, b)
            assertNull(p.error, "$b: ${p.error}")
            assertEquals(listOf(TxType.BUY, TxType.SELL), p.rows.map { it.transaction.type })
            assertEquals(bd("155.20"), p.rows[0].transaction.price)
        }
    }

    @Test fun wrongBrokerIsDetected() {
        val p = preview(csv("t212.csv", """
            Action,Time,ISIN,Ticker,Name,No. of shares,Price / share,Currency (Price / share),Total,Currency (Total),ID
            Market buy,2024-01-15 14:30:00,US0378331005,AAPL,Apple,2,185.50,USD,341.12,EUR,X
        """), Broker.DEGIRO)
        assertNotNull(p.error)
        assertEquals(Broker.TRADING_212, p.suggestedBroker)
    }

    @Test fun pdfIsRejectedWithHint() {
        val p = preview(com.portafolios.core.importer.StatementFile("x.pdf", "%PDF-1.7".toByteArray()), Broker.DEGIRO)
        assertTrue(p.error!!.contains("CSV"))
    }

    @Test fun everyBrokerHasAParser() {
        Broker.entries.forEach { assertEquals(it, com.portafolios.core.importer.StatementImporter.parserFor(it).broker) }
    }
}
