package com.mifare.cloner.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NfcManager(private val activity: Activity, private val scope: CoroutineScope) {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    private val _status = MutableStateFlow(
        NfcHwStatus(
            isNfcSupported = nfcAdapter != null,
            isNfcEnabled = nfcAdapter?.isEnabled == true
        )
    )
    val status: StateFlow<NfcHwStatus> = _status.asStateFlow()

    private val _tagDiscovered = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val tagDiscovered: SharedFlow<Tag> = _tagDiscovered.asSharedFlow()

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        scope.launch(Dispatchers.IO) {
            _tagDiscovered.emit(tag)
        }
    }

    fun resume() {
        checkStatus()
        val adapter = nfcAdapter ?: return
        if (adapter.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

            val options = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
            adapter.enableReaderMode(activity, readerCallback, flags, options)
        }
    }

    fun pause() {
        nfcAdapter?.disableReaderMode(activity)
    }

    fun checkStatus() {
        _status.value = NfcHwStatus(
            isNfcSupported = nfcAdapter != null,
            isNfcEnabled = nfcAdapter?.isEnabled == true
        )
    }
}
