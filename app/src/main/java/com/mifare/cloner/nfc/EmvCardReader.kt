package com.mifare.cloner.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.mifare.cloner.data.CardScheme
import com.mifare.cloner.data.EmvCard
import java.io.IOException

sealed class EmvReadResult {
    data class Success(val card: EmvCard) : EmvReadResult()
    data class Failure(val message: String) : EmvReadResult()
}

object EmvCardReader {

    // PPSE AID: 2PAY.SYS.DDF01
    private val PPSE_2PAY = "325041592E5359532E4444463031"
    // Contact PPSE AID: 1PAY.SYS.DDF01
    private val PPSE_1PAY = "315041592E5359532E4444463031"

    // Priority Candidate AIDs
    private val PRIORITY_AIDS = listOf(
        "A0000006581010", // МИР Classic / Debit
        "A0000006582010", // МИР Prepaid
        "A0000000031010", // Visa Debit/Credit
        "A0000000032010", // Visa Electron
        "A0000000033010", // Visa Interlink
        "A0000000041010", // Mastercard Credit/Debit
        "A0000000042010", // Mastercard
        "A0000000043060", // Maestro
        "A000000333010101", // UnionPay Debit
        "A000000333010102", // UnionPay Credit
        "A00000002501"    // Amex
    )

    fun readEmvCard(tag: Tag): EmvReadResult {
        val isoDep = IsoDep.get(tag) ?: return EmvReadResult.Failure("Тег не поддерживает IsoDep (ISO 14443-4)")

        try {
            isoDep.connect()
            isoDep.timeout = 2000

            // Step 1: SELECT PPSE (2PAY.SYS.DDF01 / 1PAY.SYS.DDF01)
            var ppseResponse = transceiveApdu(isoDep, buildSelectApdu(hexToBytes(PPSE_2PAY)))
            if (!isStatusOk(ppseResponse)) {
                ppseResponse = transceiveApdu(isoDep, buildSelectApdu(hexToBytes(PPSE_1PAY)))
            }

            val extractedAids = mutableListOf<String>()
            var applicationLabelFromPpse: String? = null

            if (isStatusOk(ppseResponse)) {
                val tlvMap = parseAllTlv(ppseResponse)
                tlvMap["4F"]?.forEach { aidBytes ->
                    extractedAids.add(bytesToHex(aidBytes).uppercase())
                }
                tlvMap["50"]?.firstOrNull()?.let {
                    applicationLabelFromPpse = String(it, Charsets.UTF_8).trim()
                }
            }

            // Combine found AIDs with priority list
            val aidsToTry = (extractedAids + PRIORITY_AIDS).distinct()

            var selectedAid: String? = null
            var applicationLabel: String? = applicationLabelFromPpse
            var cardholderName: String? = null
            var pan: String? = null
            var expiryDate: String? = null

            // Step 2 & 3: SELECT AID
            for (aidHex in aidsToTry) {
                val selectAidApdu = buildSelectApdu(hexToBytes(aidHex))
                val selectAidResponse = transceiveApdu(isoDep, selectAidApdu)

                if (isStatusOk(selectAidResponse)) {
                    selectedAid = aidHex
                    val selectTlv = parseAllTlv(selectAidResponse)

                    selectTlv["50"]?.firstOrNull()?.let {
                        applicationLabel = String(it, Charsets.UTF_8).trim()
                    }

                    // Check if PAN or Track 2 exists right in Select AID response
                    extractCardDetails(selectTlv)?.let { details ->
                        if (pan == null && details.pan.isNotEmpty()) pan = details.pan
                        if (expiryDate == null && details.expiry != null) expiryDate = details.expiry
                        if (cardholderName == null && details.cardholder != null) cardholderName = details.cardholder
                    }

                    // Step 4: Try GPO (Get Processing Options)
                    var pdolData = ByteArray(0)
                    selectTlv["9F38"]?.firstOrNull()?.let { pdolBytes ->
                        var totalLen = 0
                        var i = 0
                        while (i < pdolBytes.size) {
                            val b = pdolBytes[i].toInt() and 0xFF
                            if ((b and 0x1F) == 0x1F) i += 2 else i += 1
                            if (i < pdolBytes.size) {
                                totalLen += pdolBytes[i].toInt() and 0xFF
                                i += 1
                            }
                        }
                        pdolData = ByteArray(totalLen) // Zeros for PDOL
                    }

                    val gpoData = ByteArray(pdolData.size + 2)
                    gpoData[0] = 0x83.toByte()
                    gpoData[1] = pdolData.size.toByte()
                    System.arraycopy(pdolData, 0, gpoData, 2, pdolData.size)

                    val gpoApdu1 = ByteArray(5 + gpoData.size + 1)
                    gpoApdu1[0] = 0x80.toByte()
                    gpoApdu1[1] = 0xA8.toByte()
                    gpoApdu1[2] = 0x00.toByte()
                    gpoApdu1[3] = 0x00.toByte()
                    gpoApdu1[4] = gpoData.size.toByte()
                    System.arraycopy(gpoData, 0, gpoApdu1, 5, gpoData.size)
                    gpoApdu1[gpoApdu1.size - 1] = 0x00.toByte()

                    val gpoResp1 = transceiveApdu(isoDep, gpoApdu1)
                    if (isStatusOk(gpoResp1)) {
                        val gpoTlv = parseAllTlv(gpoResp1)
                        extractCardDetails(gpoTlv)?.let { details ->
                            if (pan == null && details.pan.isNotEmpty()) pan = details.pan
                            if (expiryDate == null && details.expiry != null) expiryDate = details.expiry
                            if (cardholderName == null && details.cardholder != null) cardholderName = details.cardholder
                        }
                    }

                    // Step 5: Read Records (SFI 1..4, Records 1..10)
                    for (sfi in 1..4) {
                        for (rec in 1..10) {
                            val p2 = (sfi shl 3) or 4
                            val readRecApdu = byteArrayOf(
                                0x00.toByte(),
                                0xB2.toByte(),
                                rec.toByte(),
                                p2.toByte(),
                                0x00.toByte()
                            )

                            val recordResp = transceiveApdu(isoDep, readRecApdu)
                            if (isStatusOk(recordResp)) {
                                val recordTlv = parseAllTlv(recordResp)
                                extractCardDetails(recordTlv)?.let { details ->
                                    if (pan == null && details.pan.isNotEmpty()) pan = details.pan
                                    if (expiryDate == null && details.expiry != null) expiryDate = details.expiry
                                    if (cardholderName == null && details.cardholder != null) cardholderName = details.cardholder
                                }
                            }
                        }
                    }

                    if (!pan.isNullOrEmpty()) {
                        break
                    }
                }
            }

            val finalPan = pan
            if (finalPan != null && finalPan.length in 13..19) {
                val scheme = detectScheme(finalPan, selectedAid, applicationLabel)
                val emvCard = EmvCard(
                    pan = finalPan,
                    expiryDate = expiryDate,
                    cardholderName = cardholderName,
                    aid = selectedAid,
                    scheme = scheme,
                    applicationLabel = applicationLabel
                )
                return EmvReadResult.Success(emvCard)
            } else {
                return EmvReadResult.Failure("Не удалось извлечь номер карты (PAN)")
            }

        } catch (e: IOException) {
            return EmvReadResult.Failure("Ошибка NFC связи IsoDep: ${e.localizedMessage}")
        } catch (e: Exception) {
            return EmvReadResult.Failure("Ошибка чтения банковской карты: ${e.localizedMessage}")
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {}
        }
    }

    private data class ExtractedDetails(
        val pan: String,
        val expiry: String?,
        val cardholder: String?
    )

    private fun extractCardDetails(tlvMap: Map<String, List<ByteArray>>): ExtractedDetails? {
        var pan: String? = null
        var expiry: String? = null
        var cardholder: String? = null

        // 1. Tag 5A: Application PAN
        tlvMap["5A"]?.firstOrNull()?.let { b ->
            val hex = bytesToHex(b).trimEnd('F', 'f')
            if (hex.all { it.isDigit() } && hex.length in 13..19) {
                pan = hex
            }
        }

        // 2. Tag 57: Track 2 Equivalent Data
        tlvMap["57"]?.firstOrNull()?.let { b ->
            val hex = bytesToHex(b).uppercase()
            // Track 2 format: PAN + 'D' or '=' + YYMM + ...
            val separatorIndex = hex.indexOfAny(charArrayOf('D', '='))
            if (separatorIndex != -1) {
                val parsedPan = hex.substring(0, separatorIndex)
                if (pan == null && parsedPan.all { it.isDigit() } && parsedPan.length in 13..19) {
                    pan = parsedPan
                }

                if (expiry == null && hex.length >= separatorIndex + 5) {
                    val yymm = hex.substring(separatorIndex + 1, separatorIndex + 5)
                    if (yymm.all { it.isDigit() }) {
                        val yy = yymm.substring(0, 2)
                        val mm = yymm.substring(2, 4)
                        expiry = "$mm/$yy"
                    }
                }
            }
        }

        // 3. Tag 5F24: Application Expiration Date (YYMMDD)
        if (expiry == null) {
            tlvMap["5F24"]?.firstOrNull()?.let { b ->
                val hex = bytesToHex(b)
                if (hex.length >= 4 && hex.substring(0, 4).all { it.isDigit() }) {
                    val yy = hex.substring(0, 2)
                    val mm = hex.substring(2, 4)
                    expiry = "$mm/$yy"
                }
            }
        }

        // 4. Tag 5F20: Cardholder Name (ASCII)
        tlvMap["5F20"]?.firstOrNull()?.let { b ->
            val name = String(b, Charsets.UTF_8).trim()
            if (name.isNotEmpty() && !name.all { it == '/' }) {
                cardholder = name.replace("/", " ")
            }
        }

        if (pan != null || expiry != null || cardholder != null) {
            return ExtractedDetails(pan ?: "", expiry, cardholder)
        }
        return null
    }

    private fun detectScheme(pan: String, aid: String?, label: String?): CardScheme {
        val cleanPan = pan.replace(" ", "").trim()
        return when {
            cleanPan.startsWith("2") ||
            (aid?.startsWith("A000000658", ignoreCase = true) == true) ||
            (label?.contains("MIR", ignoreCase = true) == true) -> CardScheme.MIR

            cleanPan.startsWith("4") ||
            (aid?.startsWith("A000000003", ignoreCase = true) == true) -> CardScheme.VISA

            cleanPan.matches(Regex("^(5[1-5]|2[2-7]).*")) ||
            (aid?.startsWith("A000000004", ignoreCase = true) == true) -> CardScheme.MASTERCARD

            cleanPan.startsWith("62") ||
            (aid?.startsWith("A000000333", ignoreCase = true) == true) -> CardScheme.UNIONPAY

            cleanPan.startsWith("34") || cleanPan.startsWith("37") -> CardScheme.AMEX

            else -> CardScheme.UNKNOWN
        }
    }

    private fun buildSelectApdu(data: ByteArray): ByteArray {
        val apdu = ByteArray(6 + data.size)
        apdu[0] = 0x00.toByte() // CLA
        apdu[1] = 0xA4.toByte() // INS (SELECT)
        apdu[2] = 0x04.toByte() // P1 (Select by name / AID)
        apdu[3] = 0x00.toByte() // P2 (First or only occurrence)
        apdu[4] = data.size.toByte() // Lc
        System.arraycopy(data, 0, apdu, 5, data.size)
        apdu[5 + data.size] = 0x00.toByte() // Le
        return apdu
    }

    private fun transceiveApdu(isoDep: IsoDep, apdu: ByteArray): ByteArray {
        return try {
            isoDep.transceive(apdu)
        } catch (_: Exception) {
            byteArrayOf(0x6F.toByte(), 0x00.toByte())
        }
    }

    private fun isStatusOk(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return (sw1 == 0x90 && sw2 == 0x00) || (sw1 == 0x61) || (sw1 == 0x9F)
    }

    /**
     * Parses BER-TLV data recursively and maps Tag Name (hex string) -> List of Byte values.
     */
    private fun parseAllTlv(data: ByteArray): Map<String, List<ByteArray>> {
        val result = mutableMapOf<String, MutableList<ByteArray>>()
        val cleanData = if (data.size >= 2 && (data[data.size - 2].toInt() and 0xFF == 0x90 || data[data.size - 2].toInt() and 0xFF == 0x61)) {
            data.copyOfRange(0, data.size - 2)
        } else {
            data
        }

        parseTlvRecursive(cleanData, 0, cleanData.size, result)
        return result
    }

    private fun parseTlvRecursive(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        result: MutableMap<String, MutableList<ByteArray>>
    ) {
        var i = offset
        val end = offset + length

        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x00 || b == 0xFF) {
                i++
                continue
            }

            // Tag parsing
            val tagStart = i
            if ((b and 0x1F) == 0x1F) {
                // Multi-byte tag
                i++
                while (i < end && (bytes[i].toInt() and 0x80) != 0) {
                    i++
                }
                if (i < end) i++
            } else {
                i++
            }

            val tagBytes = bytes.copyOfRange(tagStart, i)
            val tagHex = bytesToHex(tagBytes).uppercase()

            if (i >= end) break

            // Length parsing
            val lenByte = bytes[i].toInt() and 0xFF
            i++
            val valLen = when {
                (lenByte and 0x80) == 0 -> lenByte
                lenByte == 0x81 -> {
                    if (i < end) (bytes[i++].toInt() and 0xFF) else 0
                }
                lenByte == 0x82 -> {
                    if (i + 1 < end) {
                        val v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                        i += 2
                        v
                    } else 0
                }
                else -> 0
            }

            if (i + valLen > end || valLen < 0) {
                break
            }

            val valueBytes = bytes.copyOfRange(i, i + valLen)
            result.getOrPut(tagHex) { mutableListOf() }.add(valueBytes)

            // If constructed tag (bit 6 of first byte is set), parse children
            val isConstructed = (bytes[tagStart].toInt() and 0x20) != 0
            if (isConstructed && valLen > 0) {
                parseTlvRecursive(bytes, i, valLen, result)
            }

            i += valLen
        }
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
