package com.kingzcheung.xime.speech.service

import com.kingzcheung.xime.speech.RecognitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsrServiceAsrBackendTest {
    @Test
    fun `音频按顺序发送且只有结算结果成为final`() {
        val client = FakeClient(
            polls = ArrayDeque(
                listOf(
                    AsrServicePollResult(events = listOf(AsrServiceEvent("你", "partial"))),
                    AsrServicePollResult(events = listOf(AsrServiceEvent("你好", "final")))
                )
            ),
            finalText = "你好世界"
        )
        val finalLatch = CountDownLatch(1)
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val backend = backend(client, partials, finals, finalLatch)

        assertTrue(backend.start())
        backend.processAudioChunk(byteArrayOf(1))
        backend.processAudioChunk(byteArrayOf(2))
        waitUntil { client.pushed.size == 2 }
        backend.stop()

        assertTrue(finalLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(byteArrayOf(1).toList(), byteArrayOf(2).toList()), client.pushed.map { it.toList() })
        assertTrue(partials.containsAll(listOf("你", "你好")))
        assertEquals(listOf("你好世界"), finals)
        assertEquals(RecognitionState.IDLE, backend.getState())
    }

    @Test
    fun `握手前松手仍会完成服务端会话`() {
        val client = FakeClient(finalText = "短句", createGate = CountDownLatch(1))
        val finalLatch = CountDownLatch(1)
        val finals = mutableListOf<String>()
        val backend = backend(client, mutableListOf(), finals, finalLatch)

        assertTrue(backend.start())
        backend.processAudioChunk(byteArrayOf(7))
        backend.stop()
        client.createGate?.countDown()

        assertTrue(finalLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("短句"), finals)
        assertEquals(1, client.finishCalls)
    }

    @Test
    fun `取消会话丢弃服务端结算文本`() {
        val client = FakeClient(finalText = "不应提交", createGate = CountDownLatch(1))
        val finalLatch = CountDownLatch(1)
        val finals = mutableListOf<String>()
        val backend = backend(client, mutableListOf(), finals, finalLatch)

        assertTrue(backend.start())
        backend.cancel()
        client.createGate?.countDown()
        waitUntil { client.finishCalls == 1 }

        assertFalse(finalLatch.await(100, TimeUnit.MILLISECONDS))
        assertTrue(finals.isEmpty())
        assertEquals(RecognitionState.IDLE, backend.getState())
    }

    @Test
    fun `空结算结果产生错误而不是提交partial`() {
        val client = FakeClient(
            polls = ArrayDeque(listOf(AsrServicePollResult(text = "预览文本"))),
            finalText = ""
        )
        val errorLatch = CountDownLatch(1)
        val errors = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val backend = AsrServiceAsrBackend(client).apply {
            setCallbacks(
                onResult = { finals += it },
                onPartialResult = {},
                onStateChange = {},
                onError = { errors += it; errorLatch.countDown() }
            )
            initialize()
        }

        assertTrue(backend.start())
        waitUntil { client.pollCalls > 0 }
        backend.stop()

        assertTrue(errorLatch.await(2, TimeUnit.SECONDS))
        assertTrue(finals.isEmpty())
        assertEquals(listOf("ASR 服务未返回最终文本"), errors)
        assertEquals(1, client.finishCalls)
        assertEquals(RecognitionState.ERROR, backend.getState())
    }

    @Test
    fun `音频队列满时明确失败且不无限缓存`() {
        val client = FakeClient(finalText = "", createGate = CountDownLatch(1))
        val errorLatch = CountDownLatch(1)
        val errors = mutableListOf<String>()
        val backend = AsrServiceAsrBackend(client, queueCapacity = 1).apply {
            setCallbacks(
                onResult = {},
                onPartialResult = {},
                onStateChange = {},
                onError = { errors += it; errorLatch.countDown() }
            )
            initialize()
        }

        assertTrue(backend.start())
        backend.processAudioChunk(byteArrayOf(1))
        backend.processAudioChunk(byteArrayOf(2))

        assertTrue(errorLatch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("ASR 服务处理过慢，音频缓冲区已满"), errors)
        assertEquals(RecognitionState.ERROR, backend.getState())
        client.createGate?.countDown()
    }

    private fun backend(
        client: FakeClient,
        partials: MutableList<String>,
        finals: MutableList<String>,
        finalLatch: CountDownLatch
    ) = AsrServiceAsrBackend(client).apply {
        setCallbacks(
            onResult = { finals += it; finalLatch.countDown() },
            onPartialResult = { partials += it },
            onStateChange = {},
            onError = {}
        )
        initialize()
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("等待条件超时")
            Thread.sleep(5)
        }
    }

    private class FakeClient(
        private val polls: ArrayDeque<AsrServicePollResult> = ArrayDeque(),
        private val finalText: String,
        val createGate: CountDownLatch? = null
    ) : AsrServiceClient {
        val pushed = mutableListOf<ByteArray>()

        @Volatile
        var pollCalls = 0

        @Volatile
        var finishCalls = 0

        override fun createSession(): String {
            createGate?.await(2, TimeUnit.SECONDS)
            return "session-1"
        }

        override fun pushAudio(sessionId: String, pcm: ByteArray) {
            synchronized(pushed) { pushed += pcm }
        }

        override fun poll(sessionId: String): AsrServicePollResult {
            pollCalls++
            return synchronized(polls) {
                if (polls.isEmpty()) AsrServicePollResult() else polls.removeFirst()
            }
        }

        override fun finish(sessionId: String): AsrServiceFinalResult {
            finishCalls++
            return AsrServiceFinalResult(finalText)
        }
    }
}
