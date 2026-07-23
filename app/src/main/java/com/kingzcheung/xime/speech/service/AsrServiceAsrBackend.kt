package com.kingzcheung.xime.speech.service

import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.StreamingAsrEvent
import com.kingzcheung.xime.speech.StreamingAsrSession
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 连接独立 ASR 服务的后端。
 *
 * 网络调用只发生在单个工作线程；录音线程仅向有界队列写入 PCM，保持音频顺序并
 * 防止网络变慢时无限占用内存。
 */
class AsrServiceAsrBackend(
    private val client: AsrServiceClient,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : AsrBackend {
    override val name: String = "ASR 转写服务"

    private val audioQueue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private var session = StreamingAsrSession()
    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var state = RecognitionState.IDLE

    @Volatile
    private var worker: Thread? = null

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

    override fun initialize(): Boolean = true

    @Synchronized
    override fun start(): Boolean {
        if (worker?.isAlive == true) return false
        session = StreamingAsrSession()
        if (!session.start()) return false
        audioQueue.clear()
        stopRequested.set(false)
        cancelRequested.set(false)
        updateState(RecognitionState.PROCESSING)
        worker = Thread(::runSession, "AsrServiceSession").apply { start() }
        return true
    }

    override fun processAudioChunk(buffer: ByteArray) {
        if (stopRequested.get() || cancelRequested.get()) return
        if (!audioQueue.offer(buffer.copyOf())) {
            fail("ASR 服务处理过慢，音频缓冲区已满")
        }
    }

    override fun stop() {
        if (session.finish()) {
            stopRequested.set(true)
            updateState(RecognitionState.PROCESSING)
        }
    }

    override fun cancel() {
        if (session.cancel()) {
            cancelRequested.set(true)
            stopRequested.set(true)
            audioQueue.clear()
        }
    }

    override fun release() {
        cancel()
        worker?.interrupt()
        worker = null
        updateState(RecognitionState.IDLE)
    }

    override fun getState(): RecognitionState = state

    override fun isAvailable(): Boolean = true

    private fun runSession() {
        var sessionId: String? = null
        var serverSessionFinished = false
        try {
            sessionId = client.createSession()
            if (!session.accept(StreamingAsrEvent.SessionReady)) return
            if (!stopRequested.get()) updateState(RecognitionState.LISTENING)

            while (!cancelRequested.get()) {
                val audio = audioQueue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (audio != null) client.pushAudio(sessionId, audio)
                dispatchPartial(client.poll(sessionId))
                if (stopRequested.get() && audioQueue.isEmpty()) break
            }

            val finalResult = client.finish(sessionId)
            serverSessionFinished = true
            if (cancelRequested.get()) return
            finalResult.error?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
            val finalText = finalResult.text.trim()
            if (finalText.isEmpty()) {
                throw IllegalStateException("ASR 服务未返回最终文本")
            }
            if (session.accept(StreamingAsrEvent.Final(finalText))) {
                onResult?.invoke(finalText)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!cancelRequested.get()) fail("ASR 服务会话被中断")
        } catch (error: Exception) {
            if (!cancelRequested.get()) {
                fail(error.message?.takeIf { it.isNotBlank() } ?: "ASR 服务请求失败")
            }
        } finally {
            if (sessionId != null && cancelRequested.get() && !serverSessionFinished) {
                runCatching { client.finish(sessionId) }
            }
            audioQueue.clear()
            if (state != RecognitionState.ERROR) updateState(RecognitionState.IDLE)
            worker = null
        }
    }

    private fun dispatchPartial(result: AsrServicePollResult) {
        result.error?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        val candidates = if (result.events.isNotEmpty()) {
            result.events.map { it.text }
        } else {
            listOf(result.text)
        }
        candidates.forEach { text ->
            val clean = text.trim()
            if (session.accept(StreamingAsrEvent.Partial(clean))) {
                onPartial?.invoke(clean)
            }
        }
    }

    private fun fail(message: String) {
        if (!session.accept(StreamingAsrEvent.Failure(message))) return
        cancelRequested.set(true)
        stopRequested.set(true)
        audioQueue.clear()
        updateState(RecognitionState.ERROR)
        onError?.invoke(message)
    }

    private fun updateState(newState: RecognitionState) {
        if (state == newState) return
        state = newState
        onState?.invoke(newState)
    }

    companion object {
        private const val DEFAULT_QUEUE_CAPACITY = 100
        private const val POLL_INTERVAL_MS = 80L
    }
}
