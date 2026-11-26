package com.portafolios.core.risk

/** Puts a holding into an asset class from its symbol and sector. Unknown symbols count as equity. */
object AssetClassifier {
    private val CRYPTO_PAIR = Regex("^[A-Z0-9]+/(USD|USDT|USDC|EUR|BTC)$")
    private val CRYPTO = setOf("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "DOT", "AVAX", "LTC", "LINK", "HBAR", "THETA", "HYPE", "BNB", "MATIC", "TRX")
    private val CRYPTO_ETF = setOf("IBIT", "FBTC", "GBTC", "BITO", "ETHA", "ETHE", "BTCO", "ARKB", "HODL", "BITB")
    private val BONDS = setOf(
        "BND", "AGG", "TLT", "IEF", "SHY", "TIP", "LQD", "HYG", "JNK", "VCIT", "VCSH", "BNDX", "GOVT", "MUB", "EMB", "VGIT", "VGLT",
        "SCHZ", "IGLB", "AGGH", "IEAG", "VAGF", "IBGL", "IGLA", "SPTL", "EDV", "ZROZ",
    )
    private val CASH = setOf("SGOV", "BIL", "SHV", "USFR", "TFLO", "XEON", "CSH2", "ERNS", "MINT", "JPST", "ICSH")
    private val GOLD = setOf("GLD", "IAU", "SGOL", "PHYS", "GLDM", "SLV", "PPLT", "DBC", "PDBC", "GSG", "SGLN", "IGLN", "4GLD", "GDX")
    private val REIT = setOf("VNQ", "SCHH", "XLRE", "IYR", "REET", "VNQI", "O", "PLD", "AMT", "SPG", "VICI", "EQIX", "PSA", "WELL", "DLR")

    fun classify(symbol: String, sector: String? = null): AssetClass {
        val s = symbol.uppercase().substringBefore('.')
        return when {
            CRYPTO_PAIR.matches(symbol.uppercase()) || s in CRYPTO || s in CRYPTO_ETF || sector == "Criptomonedas" -> AssetClass.CRYPTO
            s in CASH -> AssetClass.CASH
            s in BONDS -> AssetClass.BONDS
            s in GOLD -> AssetClass.GOLD
            s in REIT || sector?.contains("Real Estate", true) == true || sector?.contains("REIT", true) == true -> AssetClass.REAL_ESTATE
            else -> AssetClass.EQUITY
        }
    }
}
