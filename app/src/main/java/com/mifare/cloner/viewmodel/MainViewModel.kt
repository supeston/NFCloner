package com.mifare.cloner.viewmodel

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mifare.cloner.data.AppSettings
import com.mifare.cloner.data.AppThemeMode
import com.mifare.cloner.data.DumpStorage
import com.mifare.cloner.data.MifareDump
import com.mifare.cloner.data.QrCodeHelper
import com.mifare.cloner.data.ScanMode
import com.mifare.cloner.feedback.FeedbackType
import com.mifare.cloner.nfc.ClonerOperationState
import com.mifare.cloner.nfc.MifareGen2Cloner
import com.mifare.cloner.nfc.ReadResult
import com.mifare.cloner.nfc.WriteResult
import com.mifare.cloner.ui.theme.AccentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val selectedTab: Int = 0,
    val clonerState: ClonerOperationState = ClonerOperationState.IdleWaitOriginal,
    val dumpsList: List<MifareDump> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val feedbackMessage: String? = null,
    val availableUpdate: com.mifare.cloner.data.ReleaseHistoryItem? = null,
    val isLatestVersion: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val releasesList: List<com.mifare.cloner.data.ReleaseHistoryItem> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val storage = DumpStorage(application.applicationContext)

    private val _uiState = MutableStateFlow(
        MainUiState(
            settings = storage.settings.value,
            dumpsList = storage.dumps.value
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _feedbackEvents = MutableSharedFlow<FeedbackType>(extraBufferCapacity = 1)
    val feedbackEvents: SharedFlow<FeedbackType> = _feedbackEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            storage.dumps.collect { list ->
                _uiState.update { it.copy(dumpsList = list) }
            }
        }
        viewModelScope.launch {
            storage.settings.collect { set ->
                _uiState.update { it.copy(settings = set) }
            }
        }
        checkForUpdatesInBackground()
    }

    fun checkForUpdatesInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                val list = com.mifare.cloner.data.ReleaseRepository.fetchAllReleases()
                val latest = list.firstOrNull()
                val isNewer = if (latest != null) {
                    val cleanTag = latest.tagName.removePrefix("v").trim()
                    com.mifare.cloner.data.ReleaseRepository.compareVersions(cleanTag, com.mifare.cloner.data.CURRENT_APP_VERSION) > 0
                } else false

                _uiState.update {
                    it.copy(
                        releasesList = list,
                        availableUpdate = if (isNewer) latest else null,
                        isLatestVersion = !isNewer && list.isNotEmpty(),
                        isCheckingUpdate = false
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setFeedbackMessage(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    fun onTagDiscovered(tag: Tag) {
        val currentTab = _uiState.value.selectedTab
        // Tab 2 (Настройки) & Tab 3 (Инфо): NFC is completely ignored
        if (currentTab == 2 || currentTab == 3) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val currentState = _uiState.value.clonerState
            val currentSettings = _uiState.value.settings

            // TAB 1: «СОХРАН» (Только быстрое чтение и сохранение в базу)
            if (currentTab == 1) {
                val techList = tag.techList.toList()
                val isMifare = techList.any { it.contains("MifareClassic", ignoreCase = true) }
                if (!isMifare) {
                    _feedbackEvents.emit(FeedbackType.ERROR)
                    setFeedbackMessage("метка не является MIFARE Classic")
                    return@launch
                }

                val res = MifareGen2Cloner.readOriginalTag(
                    tag = tag,
                    scanMode = currentSettings.scanMode,
                    customSector = currentSettings.customSector,
                    keysList = currentSettings.keysList
                )
                when (res) {
                    is ReadResult.Success -> {
                        val cleanUid = res.dump.formattedUid.replace(":", "")
                        val dumpToSave = res.dump.copy(name = "UID_$cleanUid")
                        storage.saveDump(dumpToSave)
                        _feedbackEvents.emit(FeedbackType.READ_SUCCESS)
                        setFeedbackMessage("дамп сохранен: ${dumpToSave.name}")
                    }
                    is ReadResult.Failure -> {
                        _feedbackEvents.emit(FeedbackType.ERROR)
                        setFeedbackMessage(res.message)
                    }
                }
                return@launch
            }

            // TAB 0: «СКАН» (Полный цикл клонирования: скан -> ожидание Gen2 -> запись -> автосброс)
            when (currentState) {
                is ClonerOperationState.IdleWaitOriginal,
                is ClonerOperationState.OperationError,
                is ClonerOperationState.WriteSuccess,
                is ClonerOperationState.EmvCardScanned,
                is ClonerOperationState.TransportCardScanned -> {
                    val techList = tag.techList.toList()
                    val isIsoDep = currentSettings.emvScanEnabled && techList.any { it.contains("IsoDep", ignoreCase = true) }
                    val isMifare = techList.any { it.contains("MifareClassic", ignoreCase = true) }

                    // Priority 1: Transport Cards (Troika, Strelka, Podorozhnik) if enabled
                    if (currentSettings.transportCardsEnabled && isMifare) {
                        _uiState.update { it.copy(clonerState = ClonerOperationState.ReadingOriginal("чтение транспортной карты...")) }
                        val transportRes = com.mifare.cloner.nfc.TransportCardParser.readTransportCard(tag)
                        when (transportRes) {
                            is com.mifare.cloner.nfc.TransportCardReadResult.Success -> {
                                _feedbackEvents.emit(FeedbackType.READ_SUCCESS)
                                _uiState.update {
                                    it.copy(
                                        clonerState = ClonerOperationState.TransportCardScanned(transportRes.card),
                                        feedbackMessage = "карта «${transportRes.card.type.title}» прочитана"
                                    )
                                }
                                return@launch
                            }
                            is com.mifare.cloner.nfc.TransportCardReadResult.Failure,
                            is com.mifare.cloner.nfc.TransportCardReadResult.NotATransportCard -> {
                                // Fallback to EMV or normal Mifare Classic cloning
                            }
                        }
                    }

                    // Priority 2: EMV Bank Cards if enabled
                    if (isIsoDep) {
                        _uiState.update { it.copy(clonerState = ClonerOperationState.ReadingOriginal("чтение банковской карты...")) }
                        val emvRes = com.mifare.cloner.nfc.EmvCardReader.readEmvCard(tag)
                        when (emvRes) {
                            is com.mifare.cloner.nfc.EmvReadResult.Success -> {
                                _feedbackEvents.emit(FeedbackType.READ_SUCCESS)
                                _uiState.update {
                                    it.copy(
                                        clonerState = ClonerOperationState.EmvCardScanned(emvRes.card),
                                        feedbackMessage = "карта ${emvRes.card.scheme.displayName} прочитана"
                                    )
                                }
                                return@launch
                            }
                            is com.mifare.cloner.nfc.EmvReadResult.Failure -> {
                                if (!isMifare) {
                                    _feedbackEvents.emit(FeedbackType.ERROR)
                                    _uiState.update {
                                        it.copy(clonerState = ClonerOperationState.OperationError(emvRes.message))
                                    }
                                    return@launch
                                }
                            }
                        }
                    }

                    _uiState.update { it.copy(clonerState = ClonerOperationState.ReadingOriginal("чтение оригинала...")) }

                    val res = MifareGen2Cloner.readOriginalTag(
                        tag = tag,
                        scanMode = currentSettings.scanMode,
                        customSector = currentSettings.customSector,
                        keysList = currentSettings.keysList
                    )

                    when (res) {
                        is ReadResult.Success -> {
                            storage.saveDump(res.dump)
                            _feedbackEvents.emit(FeedbackType.READ_SUCCESS)
                            _uiState.update {
                                it.copy(
                                    clonerState = ClonerOperationState.ReadyToWrite(res.dump),
                                    feedbackMessage = "дамп сохранен в базу"
                                )
                            }
                        }
                        is ReadResult.Failure -> {
                            _feedbackEvents.emit(FeedbackType.ERROR)
                            _uiState.update {
                                it.copy(clonerState = ClonerOperationState.OperationError(res.message))
                            }
                        }
                    }
                }

                is ClonerOperationState.ReadyToWrite -> {
                    val dumpToWrite = currentState.sourceDump
                    _uiState.update { it.copy(clonerState = ClonerOperationState.WritingGen2()) }

                    val res = MifareGen2Cloner.writeToGen2Tag(
                        tag = tag,
                        dump = dumpToWrite,
                        keysList = currentSettings.keysList
                    )

                    when (res) {
                        is WriteResult.Success -> {
                            _feedbackEvents.emit(FeedbackType.WRITE_SUCCESS)
                            _uiState.update {
                                it.copy(
                                    clonerState = ClonerOperationState.WriteSuccess(res.dump),
                                    feedbackMessage = "метка успешно скопирована"
                                )
                            }
                            // Auto-reset after 3 seconds
                            delay(3000)
                            if (_uiState.value.clonerState is ClonerOperationState.WriteSuccess) {
                                _uiState.update { it.copy(clonerState = ClonerOperationState.IdleWaitOriginal) }
                            }
                        }
                        is WriteResult.Failure -> {
                            _feedbackEvents.emit(FeedbackType.ERROR)
                            _uiState.update {
                                it.copy(
                                    clonerState = ClonerOperationState.OperationError(
                                        error = res.message,
                                        sourceDump = dumpToWrite,
                                        isWriteError = true
                                    )
                                )
                            }
                        }
                    }
                }

                is ClonerOperationState.ReadingOriginal,
                is ClonerOperationState.WritingGen2 -> {
                    // Operation in progress, ignore tag jitter
                }
            }
        }
    }

    fun resetToOriginalScan() {
        _uiState.update { it.copy(clonerState = ClonerOperationState.IdleWaitOriginal) }
    }

    fun retryOperation(errorState: ClonerOperationState.OperationError) {
        if (errorState.isWriteError && errorState.sourceDump != null) {
            _uiState.update {
                it.copy(clonerState = ClonerOperationState.ReadyToWrite(errorState.sourceDump))
            }
        } else {
            resetToOriginalScan()
        }
    }

    fun prepareWriteFromDump(dump: MifareDump) {
        _uiState.update {
            it.copy(
                clonerState = ClonerOperationState.ReadyToWrite(dump),
                selectedTab = 0
            )
        }
    }

    fun deleteDump(dumpId: String) {
        storage.deleteDump(dumpId)
        setFeedbackMessage("дамп удален")
    }

    fun renameDump(dumpId: String, newName: String) {
        storage.renameDump(dumpId, newName)
        setFeedbackMessage("дамп переименован")
    }

    fun importDumpFromJson(jsonContent: String): Boolean {
        val dump = storage.importDumpFromJson(jsonContent)
        return if (dump != null) {
            setFeedbackMessage("дамп импортирован: ${dump.name}")
            true
        } else {
            setFeedbackMessage("ошибка импорта дампа")
            false
        }
    }

    fun importDumpFromQr(qrContent: String): Boolean {
        val dump = QrCodeHelper.decodeDumpFromQr(qrContent)
        return if (dump != null) {
            storage.saveDump(dump)
            setFeedbackMessage("дамп получен из QR: ${dump.name}")
            true
        } else {
            setFeedbackMessage("ошибка распознавания QR дампа")
            false
        }
    }

    fun importDumpFromUri(uri: android.net.Uri, contentResolver: android.content.ContentResolver): Boolean {
        return try {
            val stream = contentResolver.openInputStream(uri) ?: return false
            val text = stream.bufferedReader().use { it.readText() }
            importDumpFromJson(text)
        } catch (e: Exception) {
            setFeedbackMessage("ошибка чтения файла дампа")
            false
        }
    }

    suspend fun exportDump(dump: MifareDump): File {
        return storage.exportDumpToFile(dump)
    }

    fun updateScanMode(mode: ScanMode) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(scanMode = mode))
    }

    fun updateCustomSector(sector: Int) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(customSector = sector.coerceIn(0, 15)))
    }

    fun updateKeysText(text: String) {
        val lines = text.split("\n", ",", " ")
            .map { it.trim().uppercase() }
            .filter { it.length == 12 && it.all { c -> "0123456789ABCDEF".contains(c) } }
            .distinct()

        val keys = if (lines.isEmpty()) listOf("FFFFFFFFFFFF") else lines
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(keysList = keys))
    }

    fun resetKeysToDefault() {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(keysList = listOf("FFFFFFFFFFFF")))
        setFeedbackMessage("ключ сброшен на FFFFFFFFFFFF")
    }

    fun updateThemeMode(mode: AppThemeMode) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(themeMode = mode))
    }

    fun updateAccentTheme(accent: AccentTheme) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(accentTheme = accent))
    }

    fun updateEmvScanEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(emvScanEnabled = enabled))
        setFeedbackMessage(if (enabled) "сканирование банковских карт включено" else "сканирование банковских карт отключено")
    }

    fun updateTransportCardsEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        storage.saveSettings(current.copy(transportCardsEnabled = enabled))
        setFeedbackMessage(if (enabled) "чтение транспортных карт включено" else "чтение транспортных карт отключено")
    }
}
