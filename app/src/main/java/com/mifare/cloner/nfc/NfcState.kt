package com.mifare.cloner.nfc

import com.mifare.cloner.data.EmvCard
import com.mifare.cloner.data.MifareDump
import com.mifare.cloner.data.TransportCard

sealed class ClonerOperationState {
    object IdleWaitOriginal : ClonerOperationState()
    data class ReadingOriginal(val message: String = "чтение оригинала...") : ClonerOperationState()
    data class ReadyToWrite(val sourceDump: MifareDump) : ClonerOperationState()
    data class WritingGen2(val message: String = "запись на заготовку Gen 2...") : ClonerOperationState()
    data class WriteSuccess(val dump: MifareDump) : ClonerOperationState()
    data class EmvCardScanned(val card: EmvCard) : ClonerOperationState()
    data class TransportCardScanned(val card: TransportCard) : ClonerOperationState()
    data class OperationError(
        val error: String,
        val sourceDump: MifareDump? = null,
        val isWriteError: Boolean = false
    ) : ClonerOperationState()
}

data class NfcHwStatus(
    val isNfcSupported: Boolean = true,
    val isNfcEnabled: Boolean = true
)
