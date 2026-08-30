package com.mifare.cloner.data

import android.content.Context
import com.mifare.cloner.ui.theme.AccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppSettings(
    val scanMode: ScanMode = ScanMode.SECTOR_0,
    val customSector: Int = 0,
    val keysList: List<String> = listOf("FFFFFFFFFFFF"),
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val accentTheme: AccentTheme = AccentTheme.CYAN,
    val emvScanEnabled: Boolean = false,
    val transportCardsEnabled: Boolean = false
) {
    val keysText: String
        get() = keysList.joinToString("\n")
}

class DumpStorage(private val context: Context) {

    private val dumpsFile = File(context.filesDir, "mifare_dumps.json")
    private val settingsFile = File(context.filesDir, "cloner_settings.json")

    private val _dumps = MutableStateFlow<List<MifareDump>>(emptyList())
    val dumps: StateFlow<List<MifareDump>> = _dumps.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        loadSettings()
        loadDumps()
    }

    private fun loadSettings() {
        try {
            if (!settingsFile.exists()) {
                return
            }
            val content = settingsFile.readText()
            val json = JSONObject(content)

            val modeStr = json.optString("scanMode", ScanMode.SECTOR_0.name)
            val scanMode = try {
                ScanMode.valueOf(modeStr)
            } catch (_: Exception) {
                ScanMode.SECTOR_0
            }

            val themeStr = json.optString("themeMode", AppThemeMode.DARK.name)
            val themeMode = try {
                AppThemeMode.valueOf(themeStr)
            } catch (_: Exception) {
                AppThemeMode.DARK
            }

            val accentStr = json.optString("accentTheme", AccentTheme.CYAN.name)
            val accentTheme = try {
                AccentTheme.valueOf(accentStr)
            } catch (_: Exception) {
                AccentTheme.CYAN
            }

            val keysArr = json.optJSONArray("keys")
            val keys = ArrayList<String>()
            if (keysArr != null && keysArr.length() > 0) {
                for (i in 0 until keysArr.length()) {
                    val k = keysArr.getString(i).trim().uppercase()
                    if (k.length == 12) {
                        keys.add(k)
                    }
                }
            }
            if (keys.isEmpty()) {
                keys.add("FFFFFFFFFFFF")
            }

            _settings.value = AppSettings(
                scanMode = scanMode,
                customSector = json.optInt("customSector", 0),
                keysList = keys,
                themeMode = themeMode,
                accentTheme = accentTheme,
                emvScanEnabled = json.optBoolean("emvScanEnabled", false),
                transportCardsEnabled = json.optBoolean("transportCardsEnabled", false)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        try {
            val json = JSONObject()
            json.put("scanMode", newSettings.scanMode.name)
            json.put("customSector", newSettings.customSector)
            json.put("themeMode", newSettings.themeMode.name)
            json.put("accentTheme", newSettings.accentTheme.name)
            json.put("emvScanEnabled", newSettings.emvScanEnabled)
            json.put("transportCardsEnabled", newSettings.transportCardsEnabled)

            val keysArr = JSONArray()
            for (k in newSettings.keysList) {
                keysArr.put(k)
            }
            json.put("keys", keysArr)

            settingsFile.writeText(json.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDumps() {
        try {
            if (!dumpsFile.exists()) {
                return
            }
            val content = dumpsFile.readText()
            val arr = JSONArray(content)
            val list = ArrayList<MifareDump>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(MifareDump.fromJson(obj))
            }
            _dumps.value = list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistDumps(list: List<MifareDump>) {
        _dumps.value = list.sortedByDescending { it.timestamp }
        try {
            val arr = JSONArray()
            for (dump in list) {
                arr.put(dump.toJson())
            }
            dumpsFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveDump(dump: MifareDump) {
        val current = _dumps.value.toMutableList()
        val index = current.indexOfFirst { it.id == dump.id }
        if (index >= 0) {
            current[index] = dump
        } else {
            current.add(0, dump)
        }
        persistDumps(current)
    }

    fun deleteDump(dumpId: String) {
        val current = _dumps.value.toMutableList()
        current.removeAll { it.id == dumpId }
        persistDumps(current)
    }

    fun renameDump(dumpId: String, newName: String) {
        val current = _dumps.value.toMutableList()
        val index = current.indexOfFirst { it.id == dumpId }
        if (index >= 0) {
            val item = current[index]
            current[index] = item.copy(name = newName.trim())
            persistDumps(current)
        }
    }

    fun importDumpFromJson(jsonContent: String): MifareDump? {
        return try {
            val obj = JSONObject(jsonContent)
            val dump = MifareDump.fromJson(obj)
            saveDump(dump)
            dump
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportDumpToFile(dump: MifareDump): File = withContext(Dispatchers.IO) {
        val safeName = dump.name.replace(Regex("[^a-zA-Z0-9а-яА-Я._-]"), "_")
        val exportFile = File(context.cacheDir, "$safeName.mfc.json")
        exportFile.writeText(dump.toJson().toString(2))
        exportFile
    }
}
