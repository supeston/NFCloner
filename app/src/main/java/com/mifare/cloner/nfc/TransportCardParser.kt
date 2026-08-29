package com.mifare.cloner.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import com.mifare.cloner.data.MifareDump
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

    // Primary sector-specific keys
    private val TROIKA_KEY_A_SEC7 = byteArrayOf(0xA7.toByte(), 0x3F.toByte(), 0x5D.toByte(), 0xC1.toByte(), 0xD3.toByte(), 0x33.toByte())
    private val TROIKA_KEY_A_SEC8 = byteArrayOf(0x1A.toByte(), 0x9C.toByte(), 0x26.toByte(), 0xE3.toByte(), 0xA8.toByte(), 0xB5.toByte())

    private val PODOROZHNIK_KEY_A_SEC4 = byteArrayOf(0x5A.toByte(), 0x36.toByte(), 0x29.toByte(), 0x48.toByte(), 0x71.toByte(), 0x0E.toByte())
    private val PODOROZHNIK_KEY_A_ALT = byteArrayOf(0x48.toByte(), 0x71.toByte(), 0x0E.toByte(), 0x5A.toByte(), 0x36.toByte(), 0x29.toByte())

    private val STRELKA_KEY_A_SEC11 = byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x33.toByte(), 0x44.toByte(), 0x55.toByte(), 0x66.toByte())

    /**
     * Parses transport card data directly from an in-memory MifareDump.
     * This avoids multiple NFC connections, tag lost errors, and thread blocks!
     */
    fun parseFromDump(dump: MifareDump): TransportCard? {
        val uid = dump.formattedUid.replace(":", "")

        // 1. Check Podorozhnik (Sector 4: Block 16)
        val block16Hex = dump.blocks[16]
        if (!block16Hex.isNullOrEmpty()) {
            val b16 = hexToBytes(block16Hex)
            val podorozhnik = parsePodorozhnikData(b16, uid, dump.sectorKeys.keys.filter { it in 4..9 })
            if (podorozhnik != null) return podorozhnik
        }

        // 2. Check Troika (Sector 7: Block 28 / Sector 8: Block 32)
        val block28Hex = dump.blocks[28]
        val block32Hex = dump.blocks[32]
        if (!block28Hex.isNullOrEmpty() || !block32Hex.isNullOrEmpty()) {
            val b28 = block28Hex?.let { hexToBytes(it) }
            val b32 = block32Hex?.let { hexToBytes(it) }
            val troika = parseTroikaData(b28, b32, uid)
            if (troika != null) return troika
        }

        // 3. Check Strelka (Sector 11: Block 44)
        val block44Hex = dump.blocks[44]
        if (!block44Hex.isNullOrEmpty()) {
            val b44 = hexToBytes(block44Hex)
            val strelka = parseStrelkaData(b44, uid)
            if (strelka != null) return strelka
        }

        return null
    }

    /**
     * Standalone direct NFC tag read for transport cards with minimal, targeted sector authentication.
     */
    fun readTransportCard(tag: Tag): TransportCardReadResult {
        val mfc = try {
            MifareClassic.get(tag)
        } catch (_: Exception) {
            null
        } ?: return TransportCardReadResult.NotATransportCard

        val uidHex = tag.id?.let { bytesToHex(it) } ?: ""

        try {
            mfc.connect()
            mfc.timeout = 1000

            // 1. Podorozhnik (Sector 4)
            if (mfc.sectorCount > 4) {
                var auth4 = false
                try {
                    auth4 = mfc.authenticateSectorWithKeyA(4, PODOROZHNIK_KEY_A_SEC4) ||
                            mfc.authenticateSectorWithKeyA(4, PODOROZHNIK_KEY_A_ALT)
                } catch (_: Exception) {}

                if (auth4) {
                    val b16 = try { mfc.readBlock(16) } catch (_: Exception) { null }
                    if (b16 != null) {
                        val pod = parsePodorozhnikData(b16, uidHex, listOf(4))
                        if (pod != null) return TransportCardReadResult.Success(pod)
                    }
                }
            }

            // 2. Troika (Sector 7 & 8)
            if (mfc.sectorCount > 8) {
                var auth7 = false
                var auth8 = false
                try { auth7 = mfc.authenticateSectorWithKeyA(7, TROIKA_KEY_A_SEC7) } catch (_: Exception) {}
                try { auth8 = mfc.authenticateSectorWithKeyA(8, TROIKA_KEY_A_SEC8) } catch (_: Exception) {}

                if (auth7 || auth8) {
                    val b28 = if (auth7) try { mfc.readBlock(28) } catch (_: Exception) { null } else null
                    val b32 = if (auth8) try { mfc.readBlock(32) } catch (_: Exception) { null } else null
                    val troika = parseTroikaData(b28, b32, uidHex)
                    if (troika != null) return TransportCardReadResult.Success(troika)
                }
            }

            // 3. Strelka (Sector 11)
            if (mfc.sectorCount > 11) {
                var auth11 = false
                try { auth11 = mfc.authenticateSectorWithKeyA(11, STRELKA_KEY_A_SEC11) } catch (_: Exception) {}
                if (auth11) {
                    val b44 = try { mfc.readBlock(44) } catch (_: Exception) { null }
                    if (b44 != null) {
                        val strelka = parseStrelkaData(b44, uidHex)
                        if (strelka != null) return TransportCardReadResult.Success(strelka)
                    }
                }
            }

            return TransportCardReadResult.NotATransportCard
        } catch (e: IOException) {
            return TransportCardReadResult.Failure("ошибка связи: ${e.localizedMessage}")
        } catch (e: Exception) {
            return TransportCardReadResult.Failure("ошибка чтения: ${e.localizedMessage}")
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    private fun isDummyBlock(b: ByteArray?): Boolean {
        if (b == null || b.size < 16) return true
        val first = b[0]
        return b.all { it == first } && (first == 0.toByte() || first == 0xFF.toByte())
    }

    private fun parsePodorozhnikData(b16: ByteArray, uidHex: String, sectors: List<Int>): TransportCard? {
        if (isDummyBlock(b16)) return null

        val kopecksLE = (b16[0].toInt() and 0xFF) or
                        ((b16[1].toInt() and 0xFF) shl 8) or
                        ((b16[2].toInt() and 0xFF) shl 16)
        val kopecksAlt = (b16[1].toInt() and 0xFF) or
                         ((b16[2].toInt() and 0xFF) shl 8)

        val val1 = kopecksLE / 100.0
        val val2 = kopecksAlt / 100.0

        val balance = if (val1 in 0.01..20000.0) val1 else if (val2 in 0.01..20000.0) val2 else 0.0

        val numRaw = ((b16[7].toLong() and 0xFFL) shl 24) or
                     ((b16[6].toLong() and 0xFFL) shl 16) or
                     ((b16[5].toLong() and 0xFFL) shl 8) or
                     (b16[4].toLong() and 0xFFL)

        val cardNumber = if (numRaw > 100000L) {
            String.format(Locale.US, "%010d", numRaw % 10000000000L)
        } else {
            uidHex.take(10).padEnd(10, '0')
        }

        return TransportCard(
            type = TransportCardType.PODOROZHNIK,
            balanceRubles = balance,
            cardNumber = cardNumber,
            uid = uidHex,
            sectorsRead = if (sectors.isNotEmpty()) sectors else listOf(4)
        )
    }

    private fun parseTroikaData(b28: ByteArray?, b32: ByteArray?, uidHex: String): TransportCard? {
        if (isDummyBlock(b28) && isDummyBlock(b32)) return null

        var balanceRubles = 0.0
        if (b32 != null && !isDummyBlock(b32) && b32.size >= 4) {
            val u1 = ((b32[0].toInt() and 0xFF) or ((b32[1].toInt() and 0xFF) shl 8) or ((b32[2].toInt() and 0xFF) shl 16)) shr 4
            val u2 = (b32[1].toInt() and 0xFF) or ((b32[2].toInt() and 0xFF) shl 8)
            val val1 = u1 / 40.0
            val val2 = u2 / 40.0
            if (val1 in 0.01..15000.0) balanceRubles = val1
            else if (val2 in 0.01..15000.0) balanceRubles = val2
        }

        if (balanceRubles == 0.0 && b28 != null && !isDummyBlock(b28) && b28.size >= 4) {
            val u1 = ((b28[0].toInt() and 0xFF) or ((b28[1].toInt() and 0xFF) shl 8) or ((b28[2].toInt() and 0xFF) shl 16)) shr 4
            val val1 = u1 / 40.0
            if (val1 in 0.01..15000.0) balanceRubles = val1
        }

        var cardNumber = ""
        if (b28 != null && b28.size >= 8 && !isDummyBlock(b28)) {
            val numRaw = ((b28[7].toLong() and 0xFFL) shl 24) or
                         ((b28[6].toLong() and 0xFFL) shl 16) or
                         ((b28[5].toLong() and 0xFFL) shl 8) or
                         (b28[4].toLong() and 0xFFL)
            if (numRaw > 100000L) {
                cardNumber = String.format(Locale.US, "%010d", numRaw % 10000000000L)
            }
        }

        if (cardNumber.isEmpty()) {
            cardNumber = uidHex.take(10).padEnd(10, '0')
        }

        val sectors = mutableListOf<Int>()
        if (b28 != null) sectors.add(7)
        if (b32 != null) sectors.add(8)

        return TransportCard(
            type = TransportCardType.TROIKA,
            balanceRubles = balanceRubles,
            cardNumber = cardNumber,
            uid = uidHex,
            sectorsRead = sectors
        )
    }

    private fun parseStrelkaData(b44: ByteArray, uidHex: String): TransportCard? {
        if (isDummyBlock(b44)) return null

        val kopecks1 = (b44[0].toInt() and 0xFF) or ((b44[1].toInt() and 0xFF) shl 8) or ((b44[2].toInt() and 0xFF) shl 16)
        val kopecks2 = (b44[1].toInt() and 0xFF) or ((b44[2].toInt() and 0xFF) shl 8)
        val val1 = kopecks1 / 100.0
        val val2 = kopecks2 / 100.0
        val balance = if (val1 in 0.0..20000.0) val1 else if (val2 in 0.0..20000.0) val2 else 0.0

        val numRaw = ((b44[7].toLong() and 0xFFL) shl 24) or
                     ((b44[6].toLong() and 0xFFL) shl 16) or
                     ((b44[5].toLong() and 0xFFL) shl 8) or
                     (b44[4].toLong() and 0xFFL)

        val cardNumber = if (numRaw > 100000L) {
            String.format(Locale.US, "%011d", numRaw % 100000000000L)
        } else {
            uidHex.take(11).padEnd(11, '0')
        }

        return TransportCard(
            type = TransportCardType.STRELKA,
            balanceRubles = balance,
            cardNumber = cardNumber,
            uid = uidHex,
            sectorsRead = listOf(11)
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}
