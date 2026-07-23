package com.kingzcheung.xime.speech.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class HttpAsrServiceClientTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<CapturedRequest>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `完整实时会话使用约定路由和Bearer鉴权`() {
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
        assertEquals(
            listOf(
                "POST /v1/live/sessions",
                "POST /v1/live/sessions/s-1/audio",
                "GET /v1/live/sessions/s-1",
                "DELETE /v1/live/sessions/s-1"
            ),
            requests.map { "${it.method} ${it.path}" }
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), requests[1].body)
        assertEquals(listOf("Bearer secret"), requests.map { it.authorization }.distinct())
    }

    @Test
    fun `健康检查确认实时能力`() {
        val health = client().checkHealth()

        assertEquals(AsrServiceHealth(ok = true, live = true, version = "1.0.0"), health)
        assertEquals("GET /health", "${requests.single().method} ${requests.single().path}")
    }

    @Test
    fun `默认拒绝明文HTTP`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HttpAsrServiceClient("http://127.0.0.1:${server.address.port}")
        }

        assertEquals("ASR 服务必须使用 HTTPS", error.message)
    }

    @Test
    fun `服务端错误只暴露消息不包含token`() {
        val client = HttpAsrServiceClient(
            baseUrl = "http://127.0.0.1:${server.address.port}/error",
            bearerToken = "do-not-leak",
            allowInsecureHttp = true
        )

        val error = assertThrows(java.io.IOException::class.java) { client.createSession() }

        assertEquals("ASR 服务请求失败：会话额度不足", error.message)
    }

    private fun client(token: String = "") = HttpAsrServiceClient(
        baseUrl = "http://127.0.0.1:${server.address.port}",
        bearerToken = token,
        allowInsecureHttp = true
    )

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes() }
        synchronized(requests) {
            requests += CapturedRequest(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                body
            )
        }
        val response = when {
            exchange.requestURI.path.startsWith("/error/") ->
                429 to "{\"error\":{\"message\":\"会话额度不足\"}}"
            exchange.requestMethod == "GET" && exchange.requestURI.path == "/health" ->
                200 to "{\"ok\":true,\"version\":\"1.0.0\",\"features\":{\"live\":true}}"
            exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/live/sessions" ->
                200 to "{\"session_id\":\"s-1\"}"
            exchange.requestURI.path.endsWith("/audio") -> 200 to "{\"ok\":true}"
            exchange.requestMethod == "GET" ->
                200 to "{\"text\":\"你好\",\"events\":[{\"type\":\"partial\",\"text\":\"你好\"}],\"error\":null}"
            exchange.requestMethod == "DELETE" -> 200 to "{\"text\":\"你好世界\",\"error\":null}"
            else -> 404 to "{\"error\":{\"message\":\"not found\"}}"
        }
        val bytes = response.second.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.first, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String,
        val body: ByteArray
    )
}
