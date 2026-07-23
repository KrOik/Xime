package com.kingzcheung.xime.speech.doutype

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DouType / 豆包输入法在线 ASR WebSocket 客户端。
 *
 * 中间结果走 [onPartial]，仅在用户结束录音或会话真正收尾时走 [onFinal]。
 * 这样上层 [VoiceRecognitionHandler] 不会在首个 interim 文本时就退出语音模式。
 */
class DouTypeWebSocketManager(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onState: (RecognitionState) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "DouTypeWS"
        private const val WS_URL = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"
        private const val AID = "401734"
        private const val FRAME_BYTES = 640
    }

    var state = RecognitionState.IDLE
        private set

    private var socket: WebSocket? = null
    private val taskId = UUID.randomUUID().toString()
    private var sessionId = ""
    private var firstFrame = true
    private var credentials: DouTypeCredentials? = null
    private val sessionReady = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastText = ""
    private val pendingAudio = ArrayList<ByteArray>()

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(): Boolean {
        if (closed.get()) return false
        state = RecognitionState.PROCESSING
        onState(state)
        sessionReady.set(false)
        finishing.set(false)
        firstFrame = true
        lastText = ""
        pendingAudio.clear()
        return try {
            credentials = DouTypeCredentialManager(context).ensure()
            val c = credentials!!
            val req = Request.Builder()
                .url("$WS_URL?aid=$AID&device_id=${c.deviceId}")
                .header("Authorization", "Bearer ${c.token}")
                .header("Sec-WebSocket-Protocol", "frontier-v2")
                .header("Device-ID", c.deviceId)
                .header("App-ID", AID)
                .header("Proto-Version", "v2")
                .header("proto-version", "v2")
                .header("User-Agent", "DoubaoIME/1.1.2")
                .header("x-tt-e-k", "${c.deviceId}+W")
                .header("x-tt-e-b", "1")
                .header("x-custom-keepalive", "true")
                .build()
            FileLogger.i(TAG, "Connecting DouType WS device=${c.deviceId}")
            socket = http.newWebSocket(req, Listener())
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Credential/connect failed: ${e.message}")
            fail("DouType 自动注册失败: ${e.message ?: "网络不可用"}")
            false
        }
    }

    fun sendAudio(data: ByteArray) {
        if (closed.get() || finishing.get()) return
        // 按 20ms/640B 帧切分并缓存，会话就绪后再发送
        var offset = 0
        while (offset < data.size) {
            val end = (offset + FRAME_BYTES).coerceAtMost(data.size)
            val slice = data.copyOfRange(offset, end)
            val frame = if (slice.size < FRAME_BYTES) {
                slice + ByteArray(FRAME_BYTES - slice.size)
            } else {
                slice
            }
            if (sessionReady.get()) {
                dispatchFrame(frame)
            } else {
                synchronized(pendingAudio) {
                    if (pendingAudio.size < 500) pendingAudio.add(frame)
                }
            }
            offset = end
        }
    }

    fun finish() {
        if (closed.get() || !finishing.compareAndSet(false, true)) return
        FileLogger.i(TAG, "finish() lastText='${lastText.take(40)}'")
        try {
            if (sessionReady.get()) {
                // 末帧 + FinishSession
                socket?.send(ByteString.of(*audioEnvelope(ByteArray(FRAME_BYTES), 9)))
                socket?.send(ByteString.of(*control("FinishSession", "")))
            } else {
                // 尚未建好会话就松手：直接以当前文本收尾
                completeWithFinal()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "finish failed: ${e.message}")
            completeWithFinal()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionReady.set(false)
        try {
            socket?.cancel()
        } catch (_: Exception) {
        }
        socket = null
        state = RecognitionState.IDLE
        onState(state)
    }

    private fun dispatchFrame(frame: ByteArray) {
        val frameState = if (firstFrame) 1 else 3
        firstFrame = false
        socket?.send(ByteString.of(*audioEnvelope(frame, frameState)))
    }

    private fun flushPendingAudio() {
        val frames = synchronized(pendingAudio) {
            val copy = ArrayList(pendingAudio)
            pendingAudio.clear()
            copy
        }
        frames.forEach { dispatchFrame(it) }
    }

    private fun completeWithFinal() {
        val text = lastText
        if (text.isNotBlank()) {
            onFinal(text)
        } else {
            // 没有识别到文本也要结束会话，避免卡在语音模式
            onFinal("")
        }
        close()
    }

    private fun fail(message: String) {
        state = RecognitionState.ERROR
        onState(state)
        onError(message)
        close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            FileLogger.i(TAG, "WS open code=${response.code}")
            // StartTask 携带完整 session payload（与引擎非 compact 路径一致）
            webSocket.send(ByteString.of(*control("StartTask", payload())))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            parse(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            FileLogger.e(TAG, "WS failure: ${t.message}")
            if (closed.get()) return
            if (finishing.get()) {
                completeWithFinal()
            } else {
                fail("DouType 连接失败: ${t.message ?: "未知错误"}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            FileLogger.i(TAG, "WS closed code=$code reason=$reason")
            if (closed.get()) return
            if (finishing.get() || lastText.isNotBlank()) {
                completeWithFinal()
            } else {
                state = RecognitionState.IDLE
                onState(state)
            }
        }
    }

    private fun payload(): String {
        val language = SettingsPreferences.getDouTypeLanguage(context)
        val resultType = SettingsPreferences.getDouTypeResultType(context)
        val showUtterances = SettingsPreferences.isDouTypeShowUtterancesEnabled(context)
        val deviceId = credentials?.deviceId.orEmpty()
        val extra = JSONObject().apply {
            put("app_name", "com.android.chrome")
            put("cell_compress_rate", 8)
            put("did", deviceId)
            put("input_mode", SettingsPreferences.getDouTypeInputMode(context))
            put("enable_asr_twopass", SettingsPreferences.isDouTypeTwoPassEnabled(context))
            put("enable_asr_threepass", SettingsPreferences.isDouTypeThreePassEnabled(context))
            put("use_twopass_retry", SettingsPreferences.isDouTypeTwoPassRetryEnabled(context))
            put("strong_ddc", SettingsPreferences.isDouTypeStrongDdcEnabled(context))
            put("remove_space_between_han_num", SettingsPreferences.isDouTypeRemoveSpacesEnabled(context))
            put("remove_space_between_han_eng", SettingsPreferences.isDouTypeRemoveSpacesEngEnabled(context))
            put("enable_print_chinese", SettingsPreferences.isDouTypePrintChineseEnabled(context))
            put("disable_user_words", SettingsPreferences.isDouTypeDisableUserWordsEnabled(context))
            put("language", language)
            put("result_type", resultType)
            put("show_utterances", showUtterances)
            put("enable_sentence_seg", SettingsPreferences.isDouTypeSentenceSegEnabled(context))
            put("enable_text_seg", SettingsPreferences.isDouTypeTextSegEnabled(context))
            put("enable_timestamp", SettingsPreferences.isDouTypeTimestampEnabled(context))
        }
        return JSONObject().apply {
            put(
                "audio_info",
                JSONObject().apply {
                    put("channel", 1)
                    put("format", "raw")
                    put("sample_rate", 16000)
                }
            )
            put("enable_punctuation", SettingsPreferences.isDouTypePunctuationEnabled(context))
            put("enable_speech_rejection", SettingsPreferences.isDouTypeSpeechRejectionEnabled(context))
            put("language", language)
            put("result_type", resultType)
            put("show_utterances", showUtterances)
            put("extra", extra)
        }.toString()
    }

    private fun control(event: String, payload: String): ByteArray {
        val c = credentials ?: error("credentials missing")
        val parts = ArrayList<ByteArray>()
        parts += str(1, c.token)
        parts += str(2, c.productKey)
        parts += str(3, "ASR")
        parts += str(4, "v2")
        parts += str(5, event)
        if (payload.isNotEmpty()) parts += str(6, payload)
        parts += str(7, taskId)
        // StartSession 需要带 session_id；其余事件有则带
        val sid = sessionId.ifBlank { taskId }
        if (event == "StartSession" || sessionId.isNotBlank()) {
            parts += str(8, sid)
        }
        return concat(*parts.toTypedArray())
    }

    private fun audioEnvelope(audio: ByteArray, frameState: Int): ByteArray {
        val rid = sessionId.ifBlank { taskId }
        return concat(
            str(3, "ASR"),
            str(5, "TaskRequest"),
            str(6, JSONObject().put("timestamp_ms", System.currentTimeMillis()).toString()),
            bytes(7, audio),
            str(8, rid),
            integer(9, frameState)
        )
    }

    private fun parse(data: ByteArray) {
        var i = 0
        var event = ""
        var statusCode: Long? = null
        var statusText = ""
        val payloads = ArrayList<JSONObject>()

        while (i < data.size) {
            val tag = data[i++].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 7
            when (wire) {
                0 -> {
                    var value = 0L
                    var shift = 0
                    while (i < data.size) {
                        val b = data[i++].toInt() and 0xFF
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (field == 5) statusCode = value
                    // 可能是 session uuid 的其它字段，忽略
                }
                2 -> {
                    var len = 0
                    var shift = 0
                    while (i < data.size) {
                        val b = data[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    val end = (i + len).coerceAtMost(data.size)
                    val raw = data.copyOfRange(i, end)
                    i = end
                    val asString = runCatching { String(raw, Charsets.UTF_8) }.getOrNull()
                    when (field) {
                        4 -> if (!asString.isNullOrBlank()) event = asString
                        6 -> if (!asString.isNullOrBlank()) statusText = asString
                        1, 2, 7, 8 -> {
                            if (asString != null && looksUuid(asString)) {
                                sessionId = asString
                            } else if (asString != null && asString.startsWith("{")) {
                                runCatching { payloads += JSONObject(asString) }
                            }
                        }
                        else -> {
                            if (asString != null && asString.startsWith("{")) {
                                runCatching { payloads += JSONObject(asString) }
                            }
                        }
                    }
                }
                1 -> i = (i + 8).coerceAtMost(data.size)
                5 -> i = (i + 4).coerceAtMost(data.size)
                else -> return
            }
        }

        if (event.isNotBlank()) {
            Log.d(TAG, "event=$event status=$statusCode text=$statusText")
        }

        when (event) {
            "TaskStarted" -> {
                socket?.send(ByteString.of(*control("StartSession", payload())))
            }
            "SessionStarted" -> {
                sessionReady.set(true)
                state = RecognitionState.LISTENING
                onState(state)
                flushPendingAudio()
                if (finishing.get()) {
                    // 用户已松手，会话刚就绪：立刻收尾
                    finish()
                }
            }
            "TaskFailed", "SessionFailed" -> {
                val msg = statusText.ifBlank { "DouType 服务启动失败 ($statusCode)" }
                FileLogger.e(TAG, msg)
                fail(msg)
                return
            }
            "SessionFinished", "TaskFinished" -> {
                completeWithFinal()
                return
            }
        }

        if (statusCode != null && statusCode >= 40_000_000L && event != "Pong") {
            fail(statusText.ifBlank { "DouType 错误码 $statusCode" })
            return
        }

        payloads.forEach { handlePayload(it) }
    }

    private fun handlePayload(payload: JSONObject) {
        val results = payload.optJSONArray("results")
        val extra = payload.optJSONObject("extra") ?: JSONObject()
        if (results == null) return

        for (idx in 0 until results.length()) {
            val item = results.optJSONObject(idx) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue

            val itemExtra = item.optJSONObject("extra") ?: JSONObject()
            val isOffline = item.optBoolean("is_offline_result") ||
                extra.optBoolean("is_offline_result") ||
                extra.optBoolean("nonstream_result") ||
                itemExtra.optBoolean("nonstream_result")
            val streamDone = item.optBoolean("stream_asr_finish")
            val vadDone = item.optBoolean("is_vad_finished") || extra.optBoolean("vad_end")
            val wireInterim = item.optBoolean("is_interim", true)
            val isFinal = isOffline || streamDone || vadDone || !wireInterim

            lastText = text
            // 录音过程中只更新 partial，避免触发 onVoiceComplete 退出语音模式
            onPartial(text)
            state = if (isFinal) RecognitionState.PROCESSING else RecognitionState.LISTENING
            onState(state)

            // 若用户已松手，收到最终结果即可收尾
            if (isFinal && finishing.get()) {
                completeWithFinal()
                return
            }
        }
    }

    private fun looksUuid(s: String): Boolean {
        if (s.startsWith("{")) return false
        if (s in setOf(
                "TaskStarted", "SessionStarted", "TaskFailed", "SessionFailed",
                "SessionFinished", "TaskFinished", "Pong", "ASR", "OK"
            )
        ) return false
        return s.length == 36 && s.count { it == '-' } == 4
    }

    private fun str(field: Int, value: String): ByteArray {
        val b = value.toByteArray(Charsets.UTF_8)
        return concat(byteArrayOf((field shl 3 or 2).toByte()), varint(b.size), b)
    }

    private fun bytes(field: Int, value: ByteArray) =
        concat(byteArrayOf((field shl 3 or 2).toByte()), varint(value.size), value)

    private fun integer(field: Int, value: Int) =
        concat(byteArrayOf((field shl 3).toByte()), varint(value))

    private fun varint(v: Int): ByteArray {
        var n = v
        val out = ArrayList<Byte>()
        do {
            var b = n and 0x7F
            n = n ushr 7
            if (n != 0) b = b or 0x80
            out.add(b.toByte())
        } while (n != 0)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }
}
