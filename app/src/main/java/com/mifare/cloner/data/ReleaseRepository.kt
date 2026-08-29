package com.mifare.cloner.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mifare.cloner.BuildConfig
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

val CURRENT_APP_VERSION: String
    get() = BuildConfig.VERSION_NAME

data class ReleaseHistoryItem(
    val tagName: String,
    val name: String,
    val changelog: String,
    val downloadUrl: String,
    val isCurrent: Boolean,
    val isNewer: Boolean,
    val isOlder: Boolean
)

object ReleaseRepository {

    fun fetchAllReleases(): List<ReleaseHistoryItem> {
        val timestamp = System.currentTimeMillis()
        val url = URL("https://api.github.com/repos/supeston/NFCloner/releases?_t=$timestamp")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "NFCloner-App")
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000

        if (connection.responseCode != 200) {
            return emptyList()
        }

        val reader = BufferedReader(InputStreamReader(connection.inputStream))
        val response = reader.readText()
        reader.close()
        connection.disconnect()

        val jsonArray = JSONArray(response)
        val list = ArrayList<ReleaseHistoryItem>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val rawTag = obj.optString("tag_name", "")
            val cleanTag = rawTag.removePrefix("v").trim()
            val name = obj.optString("name", rawTag)
            val body = obj.optString("body", "").trim()

            var downloadUrl = obj.optString("html_url", "")
            val assets = obj.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        break
                    }
                }
            }

            val cmp = compareVersions(cleanTag, CURRENT_APP_VERSION)
            val isCurrent = cmp == 0
            val isNewer = cmp > 0
            val isOlder = cmp < 0

            list.add(
                ReleaseHistoryItem(
                    tagName = rawTag,
                    name = name,
                    changelog = body,
                    downloadUrl = downloadUrl,
                    isCurrent = isCurrent,
                    isNewer = isNewer,
                    isOlder = isOlder
                )
            )
        }

        // Sort descending by semantic version so newest is always first
        list.sortWith { a, b ->
            compareVersions(b.tagName.removePrefix("v").trim(), a.tagName.removePrefix("v").trim())
        }

        return list
    }

    fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): File {
        var currentUrl = downloadUrl
        var connection: HttpURLConnection? = null
        var redirects = 0
        val maxRedirects = 6

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NFCloner-App")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl.isNullOrBlank()) {
                    throw RuntimeException("Ошибка редиректа: пустой Location")
                }
                currentUrl = newUrl
                redirects++
            } else if (status == HttpURLConnection.HTTP_OK) {
                break
            } else {
                connection.disconnect()
                throw RuntimeException("Ошибка сервера: $status")
            }
        }

        if (connection == null || connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("Не удалось установить соединение для скачивания")
        }

        val fileLength = connection.contentLength.toLong()

        // Clean up previous update apks in cache
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("NFCloner_") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}

        val cleanTag = downloadUrl.substringAfterLast("/").substringBeforeLast(".")
        val apkFile = File(context.cacheDir, "NFCloner_${cleanTag}_${System.currentTimeMillis()}.apk")

        val input = BufferedInputStream(connection.inputStream)
        val output = FileOutputStream(apkFile)
        val buffer = ByteArray(8192)
        var totalRead = 0L
        var count: Int

        while (input.read(buffer).also { count = it } != -1) {
            totalRead += count
            output.write(buffer, 0, count)
            if (fileLength > 0) {
                onProgress(totalRead.toFloat() / fileLength.toFloat(), totalRead, fileLength)
            } else {
                onProgress(0.5f, totalRead, 0L)
            }
        }

        output.flush()
        output.close()
        input.close()
        connection.disconnect()

        return apkFile
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }

        context.startActivity(intent)
    }

    fun compareVersions(v1: String, v2: String): Int {
        try {
            val p1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val p2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(p1.size, p2.size)
            for (i in 0 until maxLen) {
                val c1 = if (i < p1.size) p1[i] else 0
                val c2 = if (i < p2.size) p2[i] else 0
                if (c1 > c2) return 1
                if (c1 < c2) return -1
            }
            return 0
        } catch (e: Exception) {
            return v1.compareTo(v2)
        }
    }

    fun isVersionNewer(current: String, latest: String): Boolean {
        return compareVersions(latest, current) > 0
    }
}
