package com.mifare.cloner.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ScanMode(val title: String, val description: String) {
    SECTOR_0("сектор 0", "моментальное чтение и запись сектора 0"),
    CUSTOM_SECTOR("выбранный сектор", "чтение и запись одного выбранного сектора"),
    FULL_DUMP("фулл дамп", "полный дамп всех 16 секторов (64 блока)")
}

enum class AppThemeMode(val title: String) {
    SYSTEM("системная"),
    DARK("тёмная"),
    LIGHT("светлая")
}

data class MifareDump(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uidHex: String,
    val sak: Int = 0x08,
    val atqa: String = "0004",
    val timestamp: Long = System.currentTimeMillis(),
    val scanMode: ScanMode = ScanMode.SECTOR_0,
    val targetSector: Int = 0,
    val blocks: Map<Int, String> = emptyMap(),
    val keyUsed: String = "FFFFFFFFFFFF",
    val sectorKeys: Map<Int, String> = emptyMap()
) {
    val formattedUid: String
        get() {
            return uidHex.chunked(2).joinToString(":").uppercase()
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val totalBytes: Int
        get() = blocks.size * 16

    val blocksCount: Int
        get() = blocks.size

    val modeDescription: String
        get() = when (scanMode) {
            ScanMode.SECTOR_0 -> "сектор 0"
            ScanMode.CUSTOM_SECTOR -> "сектор $targetSector"
            ScanMode.FULL_DUMP -> "фулл дамп"
        }

    fun getBlockBytes(blockIndex: Int): ByteArray? {
        val hex = blocks[blockIndex] ?: return null
        if (hex.length != 32) return null
        return hexToBytes(hex)
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("uid", uidHex)
        json.put("sak", sak)
        json.put("atqa", atqa)
        json.put("timestamp", timestamp)
        json.put("scanMode", scanMode.name)
        json.put("targetSector", targetSector)
        json.put("keyUsed", keyUsed)

        val blocksObj = JSONObject()
        for ((idx, hex) in blocks) {
            blocksObj.put(idx.toString(), hex)
        }
        json.put("blocks", blocksObj)

        val secKeysObj = JSONObject()
        for ((sec, k) in sectorKeys) {
            secKeysObj.put(sec.toString(), k)
        }
        json.put("sectorKeys", secKeysObj)

        return json
    }

    companion object {
        fun fromJson(json: JSONObject): MifareDump {
            val blocksMap = HashMap<Int, String>()
            val blocksObj = json.optJSONObject("blocks")
            if (blocksObj != null) {
                val keys = blocksObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val idx = k.toIntOrNull()
                    if (idx != null) {
                        blocksMap[idx] = blocksObj.getString(k)
                    }
                }
            }

            val secKeysMap = HashMap<Int, String>()
            val secKeysObj = json.optJSONObject("sectorKeys")
            if (secKeysObj != null) {
                val keys = secKeysObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val sec = k.toIntOrNull()
                    if (sec != null) {
                        secKeysMap[sec] = secKeysObj.getString(k)
                    }
                }
            }

            val modeStr = json.optString("scanMode", ScanMode.SECTOR_0.name)
            val mode = try {
                ScanMode.valueOf(modeStr)
            } catch (_: Exception) {
                ScanMode.SECTOR_0
            }

            return MifareDump(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Метка ${json.optString("uid", "MIFARE")}"),
                uidHex = json.optString("uid", ""),
                sak = json.optInt("sak", 0x08),
                atqa = json.optString("atqa", "0004"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                scanMode = mode,
                targetSector = json.optInt("targetSector", 0),
                blocks = blocksMap,
                keyUsed = json.optString("keyUsed", "FFFFFFFFFFFF"),
                sectorKeys = secKeysMap
            )
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "").replace(":", "").trim()
            val len = clean.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            }
            return data
        }

        fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02X", b))
            }
            return sb.toString()
        }
    }
}
