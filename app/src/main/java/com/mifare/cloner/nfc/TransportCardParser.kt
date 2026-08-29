package com.mifare.cloner.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import com.mifare.cloner.data.TransportCard
import com.mifare.cloner.data.TransportCardType
import java.io.IOException
import java.util.Locale

sealed class TransportCardReadResult {
    data class Success(val card: TransportCard) : TransportCardReadResult()
    data class Failure(val message: String) : TransportCardReadResult()
    object NotATransportCard : TransportCardReadResult()
}

object TransportCardParser {

    // 1. Тройка (Москва)
    // Sector 7, Key A: A7 3F 5D C1 D3 33
    private val TROIKA_KEY_A_SEC7 = byteArrayOf(0xA7.toByte(), 0x3F.toByte(), 0x5D.toByte(), 0xC1.toByte(), 0xD3.toByte(), 0x33.toByte())
    // Sector 8, Key A: 1A 9C 26 E3 A8 B5
    private val TROIKA_KEY_A_SEC8 = byteArrayOf(0x1A.toByte(), 0x9C.toByte(), 0x26.toByte(), 0xE3.toByte(), 0xA8.toByte(), 0xB5.toByte())

    // 2. Подорожник (Санкт-Петербург)
    // Sector 4, Key A: 5A 36 29 48 71 0E
    private val PODOROZHNIK_KEY_A_SEC4 = byteArrayOf(0x5A.toByte(), 0x36.toByte(), 0x29.toByte(), 0x48.toByte(), 0x71.toByte(), 0x0E.toByte())

    // 3. Стрелка (Московская область)
    // Sector 11, Key A: 11 22 33 44 55 66
    private val STRELKA_KEY_A_SEC11 = byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte())

    fun readTransportCard(tag: Tag): TransportCardReadResult {
        val mfc = try {
            MifareClassic.get(tag)
        } catch (_: Exception) {
            null
        } ?: return TransportCardReadResult.NotATransportCard

        val uidHex = tag.id?.let { bytesToHex(it) } ?: ""

        try {
            mfc.connect()
            mfc.timeout = 1500

            // Try Troika (Sectors 7 & 8)
            val troikaCard = tryReadTroika(mfc, uidHex)
            if (troikaCard != null) {
                return TransportCardReadResult.Success(troikaCard)
            }

            // Try Podorozhnik (Sector 4)
            val podorozhnikCard = tryReadPodorozhnik(mfc, uidHex)
            if (podorozhnikCard != null) {
                return TransportCardReadResult.Success(podorozhnikCard)
            }

            // Try Strelka (Sector 11)
            val strelkaCard = tryReadStrelka(mfc, uidHex)
            if (strelkaCard != null) {
                return TransportCardReadResult.Success(strelkaCard)
            }

            return TransportCardReadResult.NotATransportCard
        } catch (e: IOException) {
            return TransportCardReadResult.Failure("Ошибка связи с транспортной картой: ${e.localizedMessage}")
        } catch (e: Exception) {
            return TransportCardReadResult.Failure("Ошибка чтения транспортной карты: ${e.localizedMessage}")
        } finally {
            try {
                mfc.close()
            } catch (_: Exception) {}
        }
    }

    private fun tryReadTroika(mfc: MifareClassic, uidHex: String): TransportCard? {
        try {
            if (mfc.sectorCount <= 8) return null

            var auth7 = false
            var auth8 = false

            try {
                auth7 = mfc.authenticateSectorWithKeyA(7, TROIKA_KEY_A_SEC7)
            } catch (_: Exception) {}

            try {
                auth8 = mfc.authenticateSectorWithKeyA(8, TROIKA_KEY_A_SEC8)
            } catch (_: Exception) {}

            if (!auth7 && !auth8) return null

            var block28: ByteArray? = null
            var block29: ByteArray? = null
            var block32: ByteArray? = null

            if (auth7) {
                try {
                    block28 = mfc.readBlock(28)
                    block29 = mfc.readBlock(29)
                } catch (_: Exception) {}
            }

            if (auth8) {
                try {
                    block32 = mfc.readBlock(32)
                } catch (_: Exception) {}
            }

            // Calculate Troika purse balance (1 unit = 2.5 kopecks -> rawUnits / 40.0 rubles)
            var balanceRubles = 0.0
            var foundBalance = false

            if (block32 != null && block32.size >= 4) {
                val u1 = ((block32[0].toInt() and 0xFF) or ((block32[1].toInt() and 0xFF) shl 8) or ((block32[2].toInt() and 0xFF) shl 16)) shr 4
                val u2 = (block32[1].toInt() and 0xFF) or ((block32[2].toInt() and 0xFF) shl 8)
                val u3 = (block32[0].toInt() and 0xFF) or ((block32[1].toInt() and 0xFF) shl 8)

                val val1 = u1 / 40.0
                val val2 = u2 / 40.0
                val val3 = u3 / 40.0

                if (val1 in 0.01..10000.0) {
                    balanceRubles = val1
                    foundBalance = true
                } else if (val2 in 0.01..10000.0) {
                    balanceRubles = val2
                    foundBalance = true
                } else if (val3 in 0.0..10000.0) {
                    balanceRubles = val3
                    foundBalance = true
                }
            }

            if (!foundBalance && block28 != null && block28.size >= 4) {
                val u1 = ((block28[0].toInt() and 0xFF) or ((block28[1].toInt() and 0xFF) shl 8) or ((block28[2].toInt() and 0xFF) shl 16)) shr 4
                val val1 = u1 / 40.0
                if (val1 in 0.0..10000.0) {
                    balanceRubles = val1
                }
            }

            // Card Number extraction
            var cardNumber = ""
            if (block28 != null && block28.size >= 8) {
                val numRaw = ((block28[7].toLong() and 0xFFL) shl 24) or
                             ((block28[6].toLong() and 0xFFL) shl 16) or
                             ((block28[5].toLong() and 0xFFL) shl 8) or
                             (block28[4].toLong() and 0xFFL)
                if (numRaw > 100000L) {
                    cardNumber = String.format(Locale.US, "%010d", numRaw % 10000000000L)
                }
            }

            if (cardNumber.isEmpty() && block28 != null) {
                val hexStr = bytesToHex(block28.copyOfRange(0, 5))
                val digitsOnly = hexStr.filter { it.isDigit() }
                if (digitsOnly.length >= 8) {
                    cardNumber = digitsOnly.take(10).padEnd(10, '0')
                }
            }

            if (cardNumber.isEmpty()) {
                cardNumber = uidHex.take(10).padEnd(10, '0')
            }

            val sectorsRead = mutableListOf<Int>()
            if (auth7) sectorsRead.add(7)
            if (auth8) sectorsRead.add(8)

            return TransportCard(
                type = TransportCardType.TROIKA,
                balanceRubles = balanceRubles,
                cardNumber = cardNumber,
                uid = uidHex,
                sectorsRead = sectorsRead
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun tryReadPodorozhnik(mfc: MifareClassic, uidHex: String): TransportCard? {
        try {
            if (mfc.sectorCount <= 4) return null
            val auth4 = try {
                mfc.authenticateSectorWithKeyA(4, PODOROZHNIK_KEY_A_SEC4)
            } catch (_: Exception) {
                false
            }
            if (!auth4) return null

            val block16 = mfc.readBlock(16) ?: return null

            // Balance in kopecks / 100.0
            val kopecks1 = (block16[0].toInt() and 0xFF) or ((block16[1].toInt() and 0xFF) shl 8) or ((block16[2].toInt() and 0xFF) shl 16)
            val kopecks2 = (block16[1].toInt() and 0xFF) or ((block16[2].toInt() and 0xFF) shl 8)

            val val1 = kopecks1 / 100.0
            val val2 = kopecks2 / 100.0

            val balanceRubles = if (val1 in 0.0..15000.0) val1 else if (val2 in 0.0..15000.0) val2 else 0.0

            val cardNumRaw = ((block16[7].toLong() and 0xFFL) shl 24) or
                             ((block16[6].toLong() and 0xFFL) shl 16) or
                             ((block16[5].toLong() and 0xFFL) shl 8) or
                             (block16[4].toLong() and 0xFFL)

            val cardNumber = if (cardNumRaw > 100000L) {
                String.format(Locale.US, "%010d", cardNumRaw % 10000000000L)
            } else {
                uidHex.take(10).padEnd(10, '0')
            }

            return TransportCard(
                type = TransportCardType.PODOROZHNIK,
                balanceRubles = balanceRubles,
                cardNumber = cardNumber,
                uid = uidHex,
                sectorsRead = listOf(4)
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun tryReadStrelka(mfc: MifareClassic, uidHex: String): TransportCard? {
        try {
            if (mfc.sectorCount <= 11) return null
            val auth11 = try {
                mfc.authenticateSectorWithKeyA(11, STRELKA_KEY_A_SEC11)
            } catch (_: Exception) {
                false
            }
            if (!auth11) return null

            val block44 = mfc.readBlock(44) ?: return null

            // Strelka balance in kopecks / 100.0
            val kopecks1 = (block44[0].toInt() and 0xFF) or ((block44[1].toInt() and 0xFF) shl 8) or ((block44[2].toInt() and 0xFF) shl 16)
            val kopecks2 = (block44[1].toInt() and 0xFF) or ((block44[2].toInt() and 0xFF) shl 8)

            val val1 = kopecks1 / 100.0
            val val2 = kopecks2 / 100.0

            val balanceRubles = if (val1 in 0.0..15000.0) val1 else if (val2 in 0.0..15000.0) val2 else 0.0

            val numRaw = ((block44[7].toLong() and 0xFFL) shl 24) or
                         ((block44[6].toLong() and 0xFFL) shl 16) or
                         ((block44[5].toLong() and 0xFFL) shl 8) or
                         (block44[4].toLong() and 0xFFL)

            val cardNumber = if (numRaw > 100000L) {
                String.format(Locale.US, "%011d", numRaw % 100000000000L)
            } else {
                uidHex.take(11).padEnd(11, '0')
            }

            return TransportCard(
                type = TransportCardType.STRELKA,
                balanceRubles = balanceRubles,
                cardNumber = cardNumber,
                uid = uidHex,
                sectorsRead = listOf(11)
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}
