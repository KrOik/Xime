package com.kingzcheung.xime.speech.doutype

import android.content.Context
import android.os.Handler
import android.os.Looper
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
 * 中间结果走 [onPartial]，仅在用户结束录音且 multipass/offline 校正收敛后走 [onFinal]。
 * 松手后会进入「识别优化中」等待 twopass/threepass，而不是用首个 stream_asr_finish 提前收尾。
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
        private const val FRAME_MS = 20
        /** 松手后最长等待 SessionFinished + multipass 的总时长（对齐 Python finish wait_s）。 */
        private const val FINISH_WAIT_MS = 12_000L
        /** SessionFinished 后继续等待 offline/nonstream 校正的宽限期。 */
        private const val OFFLINE_GRACE_MS = 4_000L
        /** 文本在宽限期内稳定多久即可提前收尾。 */
        private const val STABLE_MS = 800L
        /** 末帧前追加静音，便于服务端 VAD 收口（约 200ms）。 */
        private const val TRAIL_SILENCE_FRAMES = 10
        /** 按住期间会话被服务端掐断时的自动重连次数。 */
        private const val MAX_LIVE_RECONNECT = 2
    }

    var state = RecognitionState.IDLE
        private set

    private var socket: WebSocket? = null
    private var taskId = UUID.randomUUID().toString()
    private var sessionId = ""
    private var firstFrame = true
    private var frameIndex = 0
    private var audioT0Ms = 0L
    private var credentials: DouTypeCredentials? = null
    private val sessionReady = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val finishFramesSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)
    private var lastText = ""
    private var bestRank = 0
    private var gotOffline = false
    private var gotStreamFinish = false
    private var sessionFinishedSeen = false
    private var lastImproveAtMs = 0L
    private var sentAnyAudio = false
    private var liveReconnectCount = 0
    private val pendingAudio = ArrayList<ByteArray>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishTimeoutRunnable = Runnable {
        FileLogger.w(TAG, "finish wait timeout, complete with best='${lastText.take(40)}'")
        completeWithFinal()
    }
    private val offlineGraceRunnable = Runnable {
        FileLogger.i(TAG, "offline grace elapsed, complete with best='${lastText.take(40)}'")
        completeWithFinal()
    }
    private val stableCheckRunnable = object : Runnable {
        override fun run() {
            if (closed.get() || finalized.get() || !finishing.get() || !sessionFinishedSeen) return
            val stableLongEnough = System.currentTimeMillis() - lastImproveAtMs >= STABLE_MS
            // 有文本时：offline 或 stream_finish 稳定后收尾；
            // 无文本时：宽限到期由 offlineGraceRunnable 收尾，避免空转卡死。
            if (stableLongEnough && lastText.isNotBlank() && (gotOffline || gotStreamFinish)) {
                FileLogger.i(
                    TAG,
                    "multipass stable offline=$gotOffline streamFinish=$gotStreamFinish text='${lastText.take(40)}'"
                )
                completeWithFinal()
                return
            }
            mainHandler.postDelayed(this, 200L)
        }
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(): Boolean {
        if (closed.get()) {
            // isSttKeepModelInRam=true 时后端不释放，需要重置状态以支持重新连接
            closed.set(false)
            finalized.set(false)
        }
        cancelFinishTimers()
        state = RecognitionState.PROCESSING
        onState(state)
        sessionReady.set(false)
        finishing.set(false)
        finishFramesSent.set(false)
        finalized.set(false)
        firstFrame = true
        frameIndex = 0
        audioT0Ms = System.currentTimeMillis()
        // 每次连接换新 taskId，避免复用导致服务端拒帧/空结果
        taskId = UUID.randomUUID().toString()
        sessionId = ""
        lastText = ""
        bestRank = 0
        gotOffline = false
        gotStreamFinish = false
        sessionFinishedSeen = false
        lastImproveAtMs = 0L
        sentAnyAudio = false
        liveReconnectCount = 0
        pendingAudio.clear()
        return openSocket()
    }

    private fun openSocket(): Boolean {
        return try {
            credentials = credentials ?: DouTypeCredentialManager(context).ensure()
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
            FileLogger.i(TAG, "Connecting DouType WS device=${c.deviceId} task=$taskId")
            socket = http.newWebSocket(req, Listener())
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Credential/connect failed: ${e.message}")
            fail("DouType 自动注册失败: ${e.message ?: "网络不可用"}")
            false
        }
    }

    /**
     * 按住说话期间服务端掐断会话时重连，不触发 onFinal/onError，避免 UI 退回键盘。
     * 保留 lastText/bestRank 作为已识别文本。
     */
    private fun tryReconnectWhileListening(reason: String): Boolean {
        if (finishing.get() || finalized.get() || closed.get()) return false
        if (liveReconnectCount >= MAX_LIVE_RECONNECT) return false
        liveReconnectCount++
        FileLogger.w(
            TAG,
            "live reconnect #$liveReconnectCount reason=$reason keepText='${lastText.take(40)}'"
        )
        cancelFinishTimers()
        sessionReady.set(false)
        finishFramesSent.set(false)
        firstFrame = true
        frameIndex = 0
        audioT0Ms = System.currentTimeMillis()
        taskId = UUID.randomUUID().toString()
        sessionId = ""
        gotOffline = false
        gotStreamFinish = false
        sessionFinishedSeen = false
        sentAnyAudio = false
        synchronized(pendingAudio) { pendingAudio.clear() }
        // 先清空 socket 引用，再 cancel 旧连接，避免旧 Listener 回调再次进入重连/失败
        val old = socket
        socket = null
        try {
            old?.cancel()
        } catch (_: Exception) {
        }
        state = RecognitionState.PROCESSING
        onState(state)
        // 文本不清空：用户已看到的 partial 继续保留
        return openSocket()
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
        if (closed.get() || finalized.get()) return
        val firstCall = finishing.compareAndSet(false, true)
        if (!firstCall && !sessionReady.get()) return
        FileLogger.i(
            TAG,
            "finish() first=$firstCall lastText='${lastText.take(40)}' sessionReady=${sessionReady.get()}"
        )
        // 松手后进入「识别优化中」，等待 multipass/offline 结果
        if (firstCall) {
            state = RecognitionState.PROCESSING
            onState(state)
            cancelFinishTimers()
            mainHandler.postDelayed(finishTimeoutRunnable, FINISH_WAIT_MS)
        }
        try {
            if (sessionReady.get()) {
                sendFinishFrames()
            } else if (firstCall) {
                // 会话尚未就绪：先标记 finishing，等 SessionStarted 再发末帧
                FileLogger.i(TAG, "finish deferred until SessionStarted")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "finish failed: ${e.message}")
            completeWithFinal()
        }
    }

    private fun sendFinishFrames() {
        if (!finishFramesSent.compareAndSet(false, true)) return
        // 对齐 Python send_end_frame：仅在已有上行音频时补短静音，且用 dispatchFrame
        // 保证 frame_state 1/3 正确；从未发过音频时不要硬塞 frame_state=3 静音帧。
        if (sentAnyAudio) {
            repeat(TRAIL_SILENCE_FRAMES) {
                dispatchFrame(ByteArray(FRAME_BYTES))
            }
        }
        val lastState = if (firstFrame) 1 else 9
        firstFrame = false
        socket?.send(
            ByteString.of(
                *audioEnvelope(ByteArray(FRAME_BYTES), frameState = lastState, last = true)
            )
        )
        socket?.send(ByteString.of(*control("FinishSession", "")))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelFinishTimers()
        sessionReady.set(false)
        sessionId = ""
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
        sentAnyAudio = true
        socket?.send(ByteString.of(*audioEnvelope(frame, frameState = frameState, last = false)))
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
        // 防止被多个路径重复调用导致多次 onFinal（finish/onFailure/onClosed/SessionFinished/handlePayload）
        if (!finalized.compareAndSet(false, true)) return
        cancelFinishTimers()
        val text = lastText
        FileLogger.i(
            TAG,
            "completeWithFinal text='${text.take(40)}' offline=$gotOffline streamFinish=$gotStreamFinish rank=$bestRank"
        )
        if (text.isNotBlank()) {
            onFinal(text)
        } else {
            // 没有识别到文本也要结束会话，避免卡在语音模式
            onFinal("")
        }
        close()
    }

    private fun fail(message: String) {
        cancelFinishTimers()
        state = RecognitionState.ERROR
        onState(state)
        onError(message)
        close()
    }

    private fun cancelFinishTimers() {
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        mainHandler.removeCallbacks(offlineGraceRunnable)
        mainHandler.removeCallbacks(stableCheckRunnable)
    }

    private fun beginOfflineGrace() {
        if (finalized.get() || closed.get()) return
        sessionFinishedSeen = true
        // 无文本时缩短宽限，避免“识别优化中”空转
        val grace = if (lastText.isBlank()) 1_200L else OFFLINE_GRACE_MS
        lastImproveAtMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(offlineGraceRunnable)
        mainHandler.postDelayed(offlineGraceRunnable, grace)
        mainHandler.removeCallbacks(stableCheckRunnable)
        mainHandler.postDelayed(stableCheckRunnable, STABLE_MS)
        maybeCompleteAfterStable()
    }

    private fun maybeCompleteAfterStable() {
        if (!finishing.get() || !sessionFinishedSeen || finalized.get() || closed.get()) return
        val stableLongEnough = System.currentTimeMillis() - lastImproveAtMs >= STABLE_MS
        if (stableLongEnough && lastText.isNotBlank() && (gotOffline || gotStreamFinish)) {
            completeWithFinal()
        }
    }

    /**
     * 与 Python [final_text] 一致：优先更长文本，同长度时优先 offline/sentence 等更高 rank。
     * 绝不因为更短的 offline 重写而覆盖已有长结果。
     */
    private fun considerText(text: String, rank: Int): Boolean {
        if (!DouTypeResultSelector.isBetter(lastText, bestRank, text, rank)) return false
        if (text != lastText) {
            lastImproveAtMs = System.currentTimeMillis()
        }
        lastText = text
        bestRank = maxOf(bestRank, rank)
        return true
    }

    private inner class Listener : WebSocketListener() {
        private fun isCurrent(webSocket: WebSocket): Boolean = socket === webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            FileLogger.i(TAG, "WS open code=${response.code}")
            // StartTask 携带完整 session payload（与引擎非 compact 路径一致）
            webSocket.send(ByteString.of(*control("StartTask", payload())))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(webSocket) || closed.get() || finalized.get()) return
            parse(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(webSocket)) return
            FileLogger.e(TAG, "WS failure: ${t.message}")
            if (closed.get() || finalized.get()) return
            if (finishing.get()) {
                completeWithFinal()
            } else if (tryReconnectWhileListening("onFailure:${t.message}")) {
                // keep listening after reconnect
            } else {
                fail("DouType 连接失败: ${t.message ?: "未知错误"}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            FileLogger.i(TAG, "WS closed code=$code reason=$reason")
            if (closed.get() || finalized.get()) return
            if (finishing.get()) {
                completeWithFinal()
            } else if (tryReconnectWhileListening("onClosed:$code")) {
                // keep listening after reconnect
            } else if (lastText.isNotBlank()) {
                completeWithFinal()
            } else {
                fail("DouType 连接已关闭")
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

    private fun audioEnvelope(audio: ByteArray, frameState: Int, last: Boolean): ByteArray {
        // 时间戳 = 逻辑音频时间线: frameIndex * frameMs (20ms)，非 wall clock
        val ts = audioT0Ms + frameIndex * FRAME_MS.toLong()
        frameIndex++
        // extra 中携带 finish_audio/force_asr_twopass 标志，触发服务器做二遍校正
        val extra = if (last) {
            JSONObject()
                .put("finish_audio", true)
                .put("force_asr_twopass", true)
        } else {
            JSONObject()
        }
        val meta = JSONObject()
            .put("extra", extra)
            .put("timestamp_ms", ts)
            .toString()
        return concat(
            str(3, "ASR"),
            str(5, "TaskRequest"),
            str(6, meta),
            bytes(7, audio),
            str(8, taskId),
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
                if (!finishing.get()) {
                    state = RecognitionState.LISTENING
                    onState(state)
                }
                flushPendingAudio()
                if (finishing.get() && !finalized.get()) {
                    // 用户已松手、会话刚就绪：补发末帧与 FinishSession
                    try {
                        sendFinishFrames()
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "deferred finish failed: ${e.message}")
                        completeWithFinal()
                    }
                }
            }
            "TaskFailed", "SessionFailed" -> {
                val msg = statusText.ifBlank { "DouType 服务启动失败 ($statusCode)" }
                FileLogger.e(TAG, msg)
                fail(msg)
                return
            }
            "SessionFinished", "TaskFinished" -> {
                // 对齐 Python finish()：用户松手后给 offline/nonstream 宽限
                if (finishing.get()) {
                    if (lastText.isBlank() && !gotOffline) {
                        // 无任何候选时不必空等 multipass
                        completeWithFinal()
                        return
                    }
                    beginOfflineGrace()
                } else {
                    // 按住期间服务端掐断：重连续录，绝不能 onFinal 退回键盘
                    if (tryReconnectWhileListening(event)) {
                        return
                    }
                    FileLogger.w(TAG, "SessionFinished while listening, reconnect exhausted")
                    completeWithFinal()
                    return
                }
                // 不 return：同包可能还带 payload 结果
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

            val rank = DouTypeResultSelector.rank(
                isOffline = isOffline,
                streamDone = streamDone,
                vadDone = vadDone,
                wireInterim = wireInterim
            )
            if (isOffline) gotOffline = true
            if (streamDone) gotStreamFinish = true

            val updated = considerText(text, rank)
            if (updated) {
                // 录音/优化过程中只更新 partial，避免触发 onVoiceComplete 退出语音模式
                onPartial(lastText)
            }

            val optimizing = finishing.get() || isOffline || streamDone || vadDone || !wireInterim
            state = if (optimizing) RecognitionState.PROCESSING else RecognitionState.LISTENING
            onState(state)

            // 松手后绝不因 stream/vad 立刻 complete：等 SessionFinished 宽限或 offline 稳定
            if (finishing.get() && sessionFinishedSeen) {
                maybeCompleteAfterStable()
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

    private fun str(field: Int, value: String) = DouTypeProto.str(field, value)

    private fun bytes(field: Int, value: ByteArray) = DouTypeProto.bytes(field, value)

    private fun integer(field: Int, value: Int) = DouTypeProto.integer(field, value)

    private fun concat(vararg parts: ByteArray) = DouTypeProto.concat(*parts)
}
