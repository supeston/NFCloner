package com.mifare.cloner.data

enum class CardScheme(val displayName: String) {
    MIR("МИР"),
    VISA("VISA"),
    MASTERCARD("MASTERCARD"),
    UNIONPAY("UNIONPAY"),
    AMEX("AMEX"),
    UNKNOWN("EMV CARD")
}

data class EmvCard(
    val pan: String,
    val expiryDate: String?,
    val cardholderName: String?,
    val aid: String?,
    val scheme: CardScheme,
    val applicationLabel: String?
) {
    val formattedPan: String
        get() = pan.chunked(4).joinToString("  ")

    val formattedExpiry: String
        get() = expiryDate ?: "••/••"
}
