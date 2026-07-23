package com.kingzcheung.xime.speech.service

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HttpAsrServiceClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `完整实时会话使用约定路由和Bearer鉴权`() {
        enqueue(200, "{\"session_id\":\"s-1\"}")
        enqueue(200, "{\"ok\":true}")
        enqueue(200, "{\"text\":\"你好\",\"events\":[{\"type\":\"partial\",\"text\":\"你好\"}],\"error\":null}")
        enqueue(200, "{\"text\":\"你好世界\",\"error\":null}")
        val client = client(token = "secret")

        val sessionId = client.createSession()
        client.pushAudio(sessionId, byteArrayOf(1, 2, 3))
        val poll = client.poll(sessionId)
        val final = client.finish(sessionId)

        assertEquals("s-1", sessionId)
        assertEquals(listOf(AsrServiceEvent("你好", "partial")), poll.events)
        assertEquals(null, poll.error)
        assertEquals("你好世界", final.text)
        assertEquals(null, final.error)
        val requests = List(4) { server.takeRequest() }
        assertEquals(
            listOf(
                "POST /v1/live/sessions",
                "POST /v1/live/sessions/s-1/audio",
                "GET /v1/live/sessions/s-1",
                "DELETE /v1/live/sessions/s-1"
            ),
            requests.map { "${it.method} ${it.path}" }
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), requests[1].body.readByteArray())
        assertEquals(listOf("Bearer secret"), requests.map { it.getHeader("Authorization") }.distinct())
    }

    @Test
    fun `健康检查确认实时能力`() {
        enqueue(200, "{\"ok\":true,\"version\":\"1.0.0\",\"features\":{\"live\":true}}")
        val health = client().checkHealth()

        assertEquals(AsrServiceHealth(ok = true, live = true, version = "1.0.0"), health)
        val request = server.takeRequest()
        assertEquals("GET /health", "${request.method} ${request.path}")
    }

    @Test
    fun `默认拒绝明文HTTP`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HttpAsrServiceClient(server.url("/").toString())
        }

        assertEquals("ASR 服务必须使用 HTTPS", error.message)
    }

    @Test
    fun `服务端错误只暴露消息不包含token`() {
        enqueue(429, "{\"error\":{\"message\":\"会话额度不足\"}}")
        val client = HttpAsrServiceClient(
            baseUrl = server.url("/error").toString(),
            bearerToken = "do-not-leak",
            allowInsecureHttp = true
        )

        val error = assertThrows(java.io.IOException::class.java) { client.createSession() }

        assertEquals("ASR 服务请求失败：会话额度不足", error.message)
    }

    private fun client(token: String = "") = HttpAsrServiceClient(
        baseUrl = server.url("/").toString(),
        bearerToken = token,
        allowInsecureHttp = true
    )

    private fun enqueue(code: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }
}
