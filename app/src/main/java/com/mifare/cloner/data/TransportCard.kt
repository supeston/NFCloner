package com.mifare.cloner.data

import androidx.compose.ui.graphics.Color
import java.util.Locale

enum class TransportCardType(
    val title: String,
    val systemName: String,
    val city: String,
    val primaryGradient: List<Color>
) {
    TROIKA(
        title = "тройка",
        systemName = "тройка",
        city = "москва",
        primaryGradient = listOf(Color(0xFF005B5C), Color(0xFF003032))
    ),
    STRELKA(
        title = "стрелка",
        systemName = "стрелка",
        city = "подмосковье",
        primaryGradient = listOf(Color(0xFF195396), Color(0xFF0B2545))
    ),
    PODOROZHNIK(
        title = "подорожник",
        systemName = "подорожник",
        city = "питер",
        primaryGradient = listOf(Color(0xFF1B6B42), Color(0xFF0E3823))
    )
}

data class TransportCard(
    val type: TransportCardType,
    val balanceRubles: Double,
    val cardNumber: String,
    val uid: String,
    val expiryDate: String? = null,
    val tripsLeft: Int? = null,
    val sectorsRead: List<Int> = emptyList()
) {
    val formattedBalance: String
        get() = String.format(Locale.US, "%.2f ₽", balanceRubles)

    val formattedCardNumber: String
        get() {
            val clean = cardNumber.replace(" ", "").trim()
            return when {
                clean.length >= 10 -> clean.chunked(4).joinToString(" ")
                clean.isNotEmpty() -> clean
                else -> "•••• •••• ••"
            }
        }

    val formattedUid: String
        get() = uid.chunked(2).joinToString(" ").uppercase()
}
