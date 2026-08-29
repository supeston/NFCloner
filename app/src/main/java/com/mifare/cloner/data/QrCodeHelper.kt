package com.mifare.cloner.data

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object QrCodeHelper {

    private const val QR_PREFIX = "NFCDUMP:"

    fun encodeDumpToQr(dump: MifareDump): String {
        return try {
            val jsonStr = dump.toJson().toString()
            val compressed = compressGzip(jsonStr.toByteArray(StandardCharsets.UTF_8))
            val b64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
            "$QR_PREFIX$b64"
        } catch (e: Exception) {
            dump.toJson().toString()
        }
    }

    fun decodeDumpFromQr(payload: String): MifareDump? {
        val trimmed = payload.trim()
        return try {
            if (trimmed.startsWith(QR_PREFIX)) {
                val b64 = trimmed.substring(QR_PREFIX.length)
                val compressed = Base64.decode(b64, Base64.DEFAULT)
                val decompressed = decompressGzip(compressed)
                val jsonStr = String(decompressed, StandardCharsets.UTF_8)
                MifareDump.fromJson(JSONObject(jsonStr))
            } else {
                MifareDump.fromJson(JSONObject(trimmed))
            }
        } catch (e: Exception) {
            null
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 800): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1

            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun decompressGzip(compressed: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(compressed)
        val bos = ByteArrayOutputStream()
        GZIPInputStream(bis).use { gzis ->
            val buf = ByteArray(1024)
            var len: Int
            while (gzis.read(buf).also { len = it } > 0) {
                bos.write(buf, 0, len)
            }
        }
        return bos.toByteArray()
    }
}
