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

    // Troika keys (Moscow)
    private val TROIKA_KEYS = listOf(
        byteArrayOf(0xA7.toByte(), 0x3F.toByte(), 0x5D.toByte(), 0xC1.toByte(), 0xD3.toByte(), 0x33.toByte()),
        byteArrayOf(0x1A.toByte(), 0x9C.toByte(), 0x26.toByte(), 0xE3.toByte(), 0xA8.toByte(), 0xB5.toByte()),
        byteArrayOf(0xE3.toByte(), 0x51.toByte(), 0x73.toByte(), 0x49.toByte(), 0x4A.toByte(), 0x81.toByte())
    )

    // Podorozhnik keys (SPb) - rotated permutations & known keys for 1K and 4K
    private val PODOROZHNIK_KEYS = listOf(
        byteArrayOf(0x5A.toByte(), 0x36.toByte(), 0x29.toByte(), 0x48.toByte(), 0x71.toByte(), 0x0E.toByte()), // Sec 4 Key A
        byteArrayOf(0x48.toByte(), 0x71.toByte(), 0x0E.toByte(), 0x5A.toByte(), 0x36.toByte(), 0x29.toByte()), // Sec 5 Key A
        byteArrayOf(0x71.toByte(), 0x0E.toByte(), 0x5A.toByte(), 0x36.toByte(), 0x29.toByte(), 0x48.toByte()), // Sec 6 Key A
        byteArrayOf(0x29.toByte(), 0x48.toByte(), 0x71.toByte(), 0x0E.toByte(), 0x5A.toByte(), 0x36.toByte()), // Sec 7 Key A
        byteArrayOf(0x36.toByte(), 0x29.toByte(), 0x48.toByte(), 0x71.toByte(), 0x0E.toByte(), 0x5A.toByte()), // Sec 8 Key A
        byteArrayOf(0x0E.toByte(), 0x5A.toByte(), 0x36.toByte(), 0x29.toByte(), 0x48.toByte(), 0x71.toByte()), // Sec 9 Key A
        byteArrayOf(0x53.toByte(), 0x43.toByte(), 0x41.toByte(), 0x53.toByte(), 0x50.toByte(), 0x31.toByte()), // SCASP1
        byteArrayOf(0x43.toByte(), 0x53.toByte(), 0x41.toByte(), 0x53.toByte(), 0x50.toByte(), 0x31.toByte()), // CSASP1
        byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte())
    )

    // Strelka keys (MO)
    private val STRELKA_KEYS = listOf(
        byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte()),
        byteArrayOf(0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x77.toByte())
    )

    // Common standard keys
    private val STANDARD_KEYS = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte())
    )

    fun readTransportCard(tag: Tag): TransportCardReadResult {
        val mfc = try {
            MifareClassic.get(tag)
        } catch (_: Exception) {
            null
        } ?: return TransportCardReadResult.NotATransportCard

        val uidHex = tag.id?.let { bytesToHex(it) } ?: ""

        try {
            mfc.connect()
            mfc.timeout = 2000

            // 1. Try Podorozhnik first (supports 1K and 4K)
            val podorozhnikCard = tryReadPodorozhnik(mfc, uidHex)
            if (podorozhnikCard != null) {
                return TransportCardReadResult.Success(podorozhnikCard)
            }

            // 2. Try Troika (Sectors 7 & 8)
            val troikaCard = tryReadTroika(mfc, uidHex)
            if (troikaCard != null) {
                return TransportCardReadResult.Success(troikaCard)
            }

            // 3. Try Strelka (Sector 11)
            val strelkaCard = tryReadStrelka(mfc, uidHex)
            if (strelkaCard != null) {
                return TransportCardReadResult.Success(strelkaCard)
            }

            return TransportCardReadResult.NotATransportCard
        } catch (e: IOException) {
            return TransportCardReadResult.Failure("Ошибка связи с картой: ${e.localizedMessage}")
        } catch (e: Exception) {
            return TransportCardReadResult.Failure("Ошибка чтения карты: ${e.localizedMessage}")
        } finally {
            try {
                mfc.close()
            } catch (_: Exception) {}
        }
    }

    private fun authSector(mfc: MifareClassic, sector: Int, key: ByteArray): Boolean {
        try {
            if (mfc.authenticateSectorWithKeyA(sector, key)) return true
        } catch (_: Exception) {}
        try {
            if (mfc.authenticateSectorWithKeyB(sector, key)) return true
        } catch (_: Exception) {}
        return false
    }

    private fun tryReadPodorozhnik(mfc: MifareClassic, uidHex: String): TransportCard? {
        val candidateSectors = listOf(4, 5, 6, 7, 8, 3, 2, 1).filter { it < mfc.sectorCount }
        val readSectors = mutableListOf<Int>()
        var foundPodorozhnik = false
        var balanceRubles = 0.0
        var cardNumber = ""

        val allKeys = PODOROZHNIK_KEYS + STANDARD_KEYS

        for (sec in candidateSectors) {
            var authed = false
            for (k in allKeys) {
                if (authSector(mfc, sec, k)) {
                    authed = true
                    if (PODOROZHNIK_KEYS.any { it.contentEquals(k) }) {
                        foundPodorozhnik = true
                    }
                    break
                }
            }

            if (authed) {
                readSectors.add(sec)
                val firstBlock = mfc.sectorToBlock(sec)
                val blockCount = mfc.getBlockCountInSector(sec)

                for (b in 0 until minOf(blockCount, 3)) {
                    val blockIndex = firstBlock + b
                    val data = try { mfc.readBlock(blockIndex) } catch (_: Exception) { null } ?: continue

                    // Parse purse / balance
                    if (balanceRubles == 0.0 && data.size >= 4) {
                        val kopecksLE = (data[0].toInt() and 0xFF) or
                                        ((data[1].toInt() and 0xFF) shl 8) or
                                        ((data[2].toInt() and 0xFF) shl 16)
                        val kopecksAlt = (data[1].toInt() and 0xFF) or
                                         ((data[2].toInt() and 0xFF) shl 8)

                        val val1 = kopecksLE / 100.0
                        val val2 = kopecksAlt / 100.0

                        if (val1 in 0.01..15000.0) {
                            balanceRubles = val1
                            foundPodorozhnik = true
                        } else if (val2 in 0.01..15000.0) {
                            balanceRubles = val2
                            foundPodorozhnik = true
                        }
                    }

                    // Parse card number (4 bytes serial)
                    if (cardNumber.isEmpty() && data.size >= 8) {
                        val numRaw = ((data[7].toLong() and 0xFFL) shl 24) or
                                     ((data[6].toLong() and 0xFFL) shl 16) or
                                     ((data[5].toLong() and 0xFFL) shl 8) or
                                     (data[4].toLong() and 0xFFL)
                        if (numRaw > 100000L) {
                            cardNumber = String.format(Locale.US, "%010d", numRaw % 10000000000L)
                            foundPodorozhnik = true
                        }
                    }
                }
            }
        }

        if (!foundPodorozhnik && readSectors.isEmpty()) {
            return null
        }

        // If authenticated with Podorozhnik keys, return Podorozhnik card
        if (foundPodorozhnik || readSectors.contains(4) || readSectors.contains(5)) {
            if (cardNumber.isEmpty()) {
                cardNumber = uidHex.take(10).padEnd(10, '0')
            }
            return TransportCard(
                type = TransportCardType.PODOROZHNIK,
                balanceRubles = balanceRubles,
                cardNumber = cardNumber,
                uid = uidHex,
                sectorsRead = readSectors
            )
        }

        return null
    }

    private fun tryReadTroika(mfc: MifareClassic, uidHex: String): TransportCard? {
        if (mfc.sectorCount <= 8) return null
        val allTroikaKeys = TROIKA_KEYS + STANDARD_KEYS

        var auth7 = false
        var auth8 = false

        for (k in allTroikaKeys) {
            if (!auth7 && authSector(mfc, 7, k)) auth7 = true
            if (!auth8 && authSector(mfc, 8, k)) auth8 = true
            if (auth7 && auth8) break
        }

        if (!auth7 && !auth8) return null

        var block28: ByteArray? = null
        var block32: ByteArray? = null

        if (auth7) {
            try { block28 = mfc.readBlock(28) } catch (_: Exception) {}
        }
        if (auth8) {
            try { block32 = mfc.readBlock(32) } catch (_: Exception) {}
        }

        var balanceRubles = 0.0
        if (block32 != null && block32.size >= 4) {
            val u1 = ((block32[0].toInt() and 0xFF) or ((block32[1].toInt() and 0xFF) shl 8) or ((block32[2].toInt() and 0xFF) shl 16)) shr 4
            val u2 = (block32[1].toInt() and 0xFF) or ((block32[2].toInt() and 0xFF) shl 8)
            val val1 = u1 / 40.0
            val val2 = u2 / 40.0
            if (val1 in 0.01..10000.0) balanceRubles = val1
            else if (val2 in 0.01..10000.0) balanceRubles = val2
        }

        if (balanceRubles == 0.0 && block28 != null && block28.size >= 4) {
            val u1 = ((block28[0].toInt() and 0xFF) or ((block28[1].toInt() and 0xFF) shl 8) or ((block28[2].toInt() and 0xFF) shl 16)) shr 4
            val val1 = u1 / 40.0
            if (val1 in 0.0..10000.0) balanceRubles = val1
        }

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
    }

    private fun tryReadStrelka(mfc: MifareClassic, uidHex: String): TransportCard? {
        if (mfc.sectorCount <= 11) return null
        val allStrelkaKeys = STRELKA_KEYS + STANDARD_KEYS

        var auth11 = false
        for (k in allStrelkaKeys) {
            if (authSector(mfc, 11, k)) {
                auth11 = true
                break
            }
        }
        if (!auth11) return null

        val block44 = try { mfc.readBlock(44) } catch (_: Exception) { null } ?: return null

        var balanceRubles = 0.0
        val kopecks1 = (block44[0].toInt() and 0xFF) or ((block44[1].toInt() and 0xFF) shl 8) or ((block44[2].toInt() and 0xFF) shl 16)
        val kopecks2 = (block44[1].toInt() and 0xFF) or ((block44[2].toInt() and 0xFF) shl 8)
        val val1 = kopecks1 / 100.0
        val val2 = kopecks2 / 100.0
        if (val1 in 0.0..15000.0) balanceRubles = val1 else if (val2 in 0.0..15000.0) balanceRubles = val2

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
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}
