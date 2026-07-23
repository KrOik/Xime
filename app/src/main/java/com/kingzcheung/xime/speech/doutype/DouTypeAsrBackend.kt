package com.kingzcheung.xime.speech.doutype

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState

class DouTypeAsrBackend(private val context: Context) : AsrBackend {
    override val name = "DouType 在线语音识别"
    private var ws: DouTypeWebSocketManager? = null
    private var result: ((String) -> Unit)? = null
    private var state: ((RecognitionState) -> Unit)? = null
    private var error: ((String) -> Unit)? = null
    override fun setCallbacks(onResult: (String) -> Unit, onPartialResult: ((String) -> Unit)?, onStateChange: (RecognitionState) -> Unit, onError: (String) -> Unit) { result = onResult; state = onStateChange; error = onError }
    override fun initialize(): Boolean {
        val key = SettingsPreferences.getDouTypeApiKey(context)
        if (key.isBlank()) return false
        ws = DouTypeWebSocketManager(key, { result?.invoke(it) }, { state?.invoke(it) }, { error?.invoke(it) }); return true
    }
    override fun start() = ws?.connect() ?: false
    override fun processAudioChunk(buffer: ByteArray) { ws?.sendAudio(buffer) }
    override fun stop() { ws?.finish() }
    override fun cancel() { ws?.close() }
    override fun release() { ws?.close(); ws = null }
    override fun getState() = ws?.state ?: RecognitionState.IDLE
    override fun isAvailable() = SettingsPreferences.getDouTypeApiKey(context).isNotBlank()
}
