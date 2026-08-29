package com.mifare.cloner.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.mifare.cloner.data.CardScheme
import com.mifare.cloner.data.EmvCard
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class EmvReadResult {
    data class Success(val card: EmvCard) : EmvReadResult()
    data class Failure(val message: String) : EmvReadResult()
}

object EmvCardReader {

    // PPSE AIDs
    private const val PPSE_2PAY = "325041592E5359532E4444463031" // 2PAY.SYS.DDF01 (Contactless)
    private const val PPSE_1PAY = "315041592E5359532E4444463031" // 1PAY.SYS.DDF01 (Contact)

    // Priority Candidate AIDs (Russia / International)
    private val PRIORITY_AIDS = listOf(
        "A0000006581010",   // МИР Debit / Credit (T-Bank, Sber, etc.)
        "A0000006582010",   // МИР Prepaid
        "A0000006582011",   // МИР Prepaid 2
        "A0000006580101",   // МИР NSPK
        "A0000000031010",   // Visa Debit/Credit
        "A0000000032010",   // Visa Electron
        "A0000000033010",   // Visa Interlink
        "A0000000041010",   // Mastercard Credit/Debit
        "A0000000042010",   // Mastercard
        "A0000000043060",   // Maestro
        "A000000333010101", // UnionPay Debit
        "A000000333010102", // UnionPay Credit
        "A00000002501"      // Amex
    )

    fun readEmvCard(tag: Tag): EmvReadResult {
        val isoDep = IsoDep.get(tag) ?: return EmvReadResult.Failure("Тег не поддерживает IsoDep (ISO 14443-4)")

        try {
            isoDep.connect()
            isoDep.timeout = 2500

            // Step 1: SELECT PPSE
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

            val aidsToTry = (extractedAids + PRIORITY_AIDS).distinct()

            var selectedAid: String? = null
            var applicationLabel: String? = applicationLabelFromPpse
            var cardholderName: String? = null
            var pan: String? = null
            var expiryDate: String? = null

            for (aidHex in aidsToTry) {
                val selectAidApdu = buildSelectApdu(hexToBytes(aidHex))
                val selectAidResponse = transceiveApdu(isoDep, selectAidApdu)

                if (isStatusOk(selectAidResponse)) {
                    selectedAid = aidHex
                    val selectTlv = parseAllTlv(selectAidResponse)

                    selectTlv["50"]?.firstOrNull()?.let {
                        applicationLabel = String(it, Charsets.UTF_8).trim()
                    }

                    // Extract details directly from SELECT AID response (if available)
                    extractCardDetails(selectTlv, selectAidResponse)?.let { details ->
                        if (pan == null && details.pan.isNotEmpty()) pan = details.pan
                        if (expiryDate == null && details.expiry != null) expiryDate = details.expiry
                        if (cardholderName == null && details.cardholder != null) cardholderName = details.cardholder
                    }

                    // Step 2: Build GPO with intelligent PDOL
                    val pdolBytes = selectTlv["9F38"]?.firstOrNull()
                    val gpoData = if (pdolBytes != null && pdolBytes.isNotEmpty()) {
                        buildPdolData(pdolBytes)
                    } else {
                        ByteArray(0)
                    }

                    val gpoPayload = ByteArray(2 + gpoData.size).apply {
                        this[0] = 0x83.toByte()
                        this[1] = gpoData.size.toByte()
                        if (gpoData.isNotEmpty()) {
                            System.arraycopy(gpoData, 0, this, 2, gpoData.size)
                        }
                    }

                    val gpoApdu = ByteArray(5 + gpoPayload.size + 1).apply {
                        this[0] = 0x80.toByte()
                        this[1] = 0xA8.toByte()
                        this[2] = 0x00.toByte()
                        this[3] = 0x00.toByte()
                        this[4] = gpoPayload.size.toByte()
                        System.arraycopy(gpoPayload, 0, this, 5, gpoPayload.size)
                        this[5 + gpoPayload.size] = 0x00.toByte()
                    }

                    val gpoResp = transceiveApdu(isoDep, gpoApdu)
                    var aflEntries = emptyList<Pair<Int, IntRange>>()

                    if (isStatusOk(gpoResp)) {
                        val gpoTlv = parseAllTlv(gpoResp)
                        extractCardDetails(gpoTlv, gpoResp)?.let { details ->
                            if (pan == null && details.pan.isNotEmpty()) pan = details.pan
                            if (expiryDate == null && details.expiry != null) expiryDate = details.expiry
                            if (cardholderName == null && details.cardholder != null) cardholderName = details.cardholder
                        }
                        aflEntries = extractAflEntries(gpoTlv, gpoResp)
                    }

                    // Step 3: READ RECORDS
                    val recordsToRead = if (aflEntries.isNotEmpty()) {
                        aflEntries
                    } else {
                        // Exhaustive fallback for SFI 1..15, rec 1..6
                        (1..15).map { sfi -> Pair(sfi, 1..6) }
                    }

                    for ((sfi, range) in recordsToRead) {
                        for (rec in range) {
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
                                extractCardDetails(recordTlv, recordResp)?.let { details ->
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

    private fun buildPdolData(pdolBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        val currentDate = SimpleDateFormat("yyMMdd", Locale.US).format(Date())

        while (i < pdolBytes.size) {
            val b = pdolBytes[i].toInt() and 0xFF
            val tagStart = i
            if ((b and 0x1F) == 0x1F) {
                i++
                while (i < pdolBytes.size && (pdolBytes[i].toInt() and 0x80) != 0) {
                    i++
                }
                if (i < pdolBytes.size) i++
            } else {
                i++
            }

            val tagHex = bytesToHex(pdolBytes.copyOfRange(tagStart, i)).uppercase()
            val len = if (i < pdolBytes.size) (pdolBytes[i++].toInt() and 0xFF) else 0

            val valBytes = when (tagHex) {
                "9F66" -> hexToBytes("36204000") // Terminal Transaction Qualifiers (Contactless Reader)
                "9F02" -> ByteArray(len)         // Amount, Authorized (000000000000)
                "9F03" -> ByteArray(len)         // Amount, Other (000000000000)
                "9F1A" -> hexToBytes("0643")     // Terminal Country Code (643 - Russia)
                "95"   -> ByteArray(len)         // Terminal Verification Results (0000000000)
                "5F2A" -> hexToBytes("0643")     // Transaction Currency Code (643 - RUB)
                "9A"   -> hexToBytes(currentDate)// Transaction Date
                "9C"   -> ByteArray(len)         // Transaction Type (00 = Purchase)
                "9F37" -> hexToBytes("12345678") // Unpredictable Number
                "9F35" -> byteArrayOf(0x22.toByte()) // Terminal Type
                else   -> ByteArray(len)
            }

            val chunk = ByteArray(len)
            System.arraycopy(valBytes, 0, chunk, 0, minOf(valBytes.size, len))
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun extractAflEntries(gpoTlv: Map<String, List<ByteArray>>, rawGpoResp: ByteArray): List<Pair<Int, IntRange>> {
        val entries = mutableListOf<Pair<Int, IntRange>>()
        val aflBytes = gpoTlv["94"]?.firstOrNull()
            ?: gpoTlv["80"]?.firstOrNull()?.let { if (it.size >= 2) it.copyOfRange(2, it.size) else null }

        if (aflBytes != null) {
            var i = 0
            while (i + 3 < aflBytes.size) {
                val sfi = (aflBytes[i].toInt() and 0xF8) shr 3
                val firstRec = aflBytes[i + 1].toInt() and 0xFF
                val lastRec = aflBytes[i + 2].toInt() and 0xFF
                if (sfi in 1..31 && firstRec in 1..30 && lastRec >= firstRec) {
                    entries.add(Pair(sfi, firstRec..lastRec))
                }
                i += 4
            }
        }
        return entries
    }

    private data class ExtractedDetails(
        val pan: String,
        val expiry: String?,
        val cardholder: String?
    )

    private fun extractCardDetails(tlvMap: Map<String, List<ByteArray>>, rawBytes: ByteArray): ExtractedDetails? {
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
            parseTrack2(bytesToHex(b))?.let { (p, exp) ->
                if (pan == null) pan = p
                if (expiry == null) expiry = exp
            }
        }

        // 3. Tag 9F6B: Track 2 Data (Contactless MIR / Visa / MC)
        tlvMap["9F6B"]?.firstOrNull()?.let { b ->
            parseTrack2(bytesToHex(b))?.let { (p, exp) ->
                if (pan == null) pan = p
                if (expiry == null) expiry = exp
            }
        }

        // 4. Tag 56: Track 1 Data
        tlvMap["56"]?.firstOrNull()?.let { b ->
            parseTrack1(b)?.let { (p, exp, name) ->
                if (pan == null) pan = p
                if (expiry == null) expiry = exp
                if (cardholder == null) cardholder = name
            }
        }

        // 5. Tag 5F24: Application Expiration Date (YYMMDD)
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

        // 6. Tag 5F20: Cardholder Name (ASCII)
        if (cardholder == null) {
            tlvMap["5F20"]?.firstOrNull()?.let { b ->
                val name = String(b, Charsets.UTF_8).trim()
                if (name.isNotEmpty() && !name.all { it == '/' }) {
                    cardholder = name.replace("/", " ")
                }
            }
        }

        // Fallback: Raw hex scan for Track 2 delimiter (D or =) if TLV missed it
        if (pan == null) {
            val rawHex = bytesToHex(rawBytes).uppercase()
            parseRawTrack2Pattern(rawHex)?.let { (p, exp) ->
                pan = p
                if (expiry == null) expiry = exp
            }
        }

        if (pan != null || expiry != null || cardholder != null) {
            return ExtractedDetails(pan ?: "", expiry, cardholder)
        }
        return null
    }

    private fun parseTrack2(hex: String): Pair<String, String?>? {
        val upper = hex.uppercase()
        val sepIdx = upper.indexOfAny(charArrayOf('D', '='))
        if (sepIdx != -1) {
            val parsedPan = upper.substring(0, sepIdx)
            if (parsedPan.all { it.isDigit() } && parsedPan.length in 13..19) {
                var exp: String? = null
                if (upper.length >= sepIdx + 5) {
                    val yymm = upper.substring(sepIdx + 1, sepIdx + 5)
                    if (yymm.all { it.isDigit() }) {
                        val yy = yymm.substring(0, 2)
                        val mm = yymm.substring(2, 4)
                        exp = "$mm/$yy"
                    }
                }
                return Pair(parsedPan, exp)
            }
        }
        return null
    }

    private fun parseTrack1(bytes: ByteArray): Triple<String, String?, String?>? {
        try {
            val text = String(bytes, Charsets.UTF_8)
            val regex = Regex("""B(\d{13,19})\^([^^]*)\^(\d{2})(\d{2})""")
            val match = regex.find(text)
            if (match != null) {
                val pan = match.groupValues[1]
                val name = match.groupValues[2].replace("/", " ").trim().ifEmpty { null }
                val yy = match.groupValues[3]
                val mm = match.groupValues[4]
                return Triple(pan, "$mm/$yy", name)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseRawTrack2Pattern(rawHex: String): Pair<String, String?>? {
        val pattern = Regex("""(\d{13,19})[D=](\d{2})(\d{2})""")
        val match = pattern.find(rawHex)
        if (match != null) {
            val pan = match.groupValues[1]
            val yy = match.groupValues[2]
            val mm = match.groupValues[3]
            return Pair(pan, "$mm/$yy")
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

            val tagStart = i
            if ((b and 0x1F) == 0x1F) {
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
