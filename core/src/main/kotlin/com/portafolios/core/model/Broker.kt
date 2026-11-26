package com.portafolios.core.model

/**
 * Brokers supported by the statement importer. Same list as the reference web tool.
 * [fileHint] is shown to the user in the import screen so they know which export to download.
 */
enum class Broker(val displayName: String, val fileHint: String) {
    INTERACTIVE_BROKERS("Interactive Brokers", "Activity Statement en CSV (Informes > Extractos)"),
    CHARLES_SCHWAB("Charles Schwab", "Historial de transacciones en CSV"),
    REVOLUT("Revolut", "Extracto de trading en CSV (Account statement > Excel/CSV)"),
    XTB("XTB", "Historial de la cuenta en Excel (.xlsx), hoja Cash operations"),
    ROBINHOOD("Robinhood", "Account activity report en CSV"),
    TRADING_212("Trading 212", "Historial exportado en CSV"),
    DEGIRO("Degiro", "Transacciones en CSV (Actividad > Transacciones > Exportar)"),
    YAHOO_FINANCE("Yahoo Finance", "Exportación de cartera en CSV"),
    ETORO("eToro", "Account statement en Excel (.xlsx), hoja Account Activity"),
    ETRADE("E*Trade", "Historial de transacciones en CSV"),
    QUANTFURY("Quantfury", "Historial en CSV (importador genérico)"),
    SNOWBALL_ANALYTICS("Snowball Analytics", "Exportación de operaciones en CSV"),
    WEBULL("Webull", "Historial de órdenes en CSV"),
    TASTYTRADE("tastytrade", "Historial de transacciones en CSV"),
    APEX_CLEARING("Apex Clearing", "Historial de transacciones en CSV (importador genérico)"),
    ROBINHOOD_CRYPTO("Robinhood Crypto", "Account activity report en CSV"),
    OTHER("Otro", "CSV o Excel con columnas de fecha, símbolo, tipo, cantidad y precio"),
}
