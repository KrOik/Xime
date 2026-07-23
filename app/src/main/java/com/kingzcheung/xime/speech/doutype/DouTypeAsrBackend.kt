package com.kingzcheung.xime.speech.doutype

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState

class DouTypeAsrBackend(private val context: Context) : AsrBackend {
    override val name = "DouType 在线语音识别"

    private var ws: DouTypeWebSocketManager? = null
    private var onResult: ((String) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onState: ((RecognitionState) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResult = onResult
        this.onPartial = onPartialResult
        this.onState = onStateChange
        this.onError = onError
    }

    override fun initialize(): Boolean {
        ws = DouTypeWebSocketManager(
            context = context,
            onPartial = { text -> onPartial?.invoke(text) },
            onFinal = { text -> onResult?.invoke(text) },
            onState = { state -> onState?.invoke(state) },
            onError = { err -> onError?.invoke(err) }
        )
        return true
    }

    override fun start(): Boolean = ws?.connect() ?: false

    override fun processAudioChunk(buffer: ByteArray) {
        ws?.sendAudio(buffer)
    }

    override fun stop() {
        ws?.finish()
    }

    override fun cancel() {
        ws?.close()
    }

    override fun release() {
        ws?.close()
        ws = null
    }

    override fun getState(): RecognitionState = ws?.state ?: RecognitionState.IDLE

    override fun isAvailable(): Boolean {
        if (SettingsPreferences.isSttUseLocal(context)) return false
        if (SettingsPreferences.getSttProvider(context) != "doutype") return false
        return SettingsPreferences.isDouTypeEnabled(context)
    }
}
