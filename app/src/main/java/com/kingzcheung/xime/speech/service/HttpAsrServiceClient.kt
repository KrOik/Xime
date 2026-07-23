package com.kingzcheung.xime.speech.service

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpAsrServiceClient(
    baseUrl: String,
    private val bearerToken: String = "",
    allowInsecureHttp: Boolean = false,
    private val http: OkHttpClient = defaultHttpClient()
) : AsrServiceClient {
    private val healthUrl: HttpUrl
    private val sessionsUrl: HttpUrl

    init {
        val normalized = if (allowInsecureHttp) {
            baseUrl.trim().trimEnd('/')
        } else {
            AsrServiceConfigValidator.normalizeHttpsUrl(baseUrl)
        }
        val root = normalized.toHttpUrl()
        require(allowInsecureHttp || root.scheme == "https") { "ASR 服务必须使用 HTTPS" }
        require(root.username.isEmpty() && root.password.isEmpty()) {
            "ASR 服务地址不能包含用户名或密码"
        }
        require(root.query == null && root.fragment == null) { "ASR 服务地址不能包含查询参数或片段" }
        healthUrl = root.newBuilder().addPathSegment("health").build()
        sessionsUrl = root.newBuilder()
            .addPathSegments("v1/live/sessions")
            .build()
    }

    fun checkHealth(): AsrServiceHealth {
        val json = executeJson(request(healthUrl).get().build())
        return AsrServiceHealth(
            ok = json.optBoolean("ok", false),
            live = json.optJSONObject("features")?.optBoolean("live", false) ?: false,
            version = json.optString("version")
        )
    }

    override fun createSession(): String {
        val request = request(sessionsUrl)
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val id = executeJson(request).optString("session_id").trim()
        if (id.isEmpty()) throw IOException("ASR 服务未返回 session_id")
        return id
    }

    override fun pushAudio(sessionId: String, pcm: ByteArray) {
        val request = request(sessionUrl(sessionId, "audio"))
            .post(pcm.toRequestBody(PCM_MEDIA_TYPE))
            .build()
        executeJson(request)
    }

    override fun poll(sessionId: String): AsrServicePollResult {
        val json = executeJson(request(sessionUrl(sessionId)).get().build())
        val events = buildList {
            val array = json.optJSONArray("events") ?: return@buildList
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) {
                    add(AsrServiceEvent(text, item.optString("type", "partial")))
                }
            }
        }
        return AsrServicePollResult(
            text = json.optString("text"),
            events = events,
            error = optionalError(json)
        )
    }

    override fun finish(sessionId: String): AsrServiceFinalResult {
        val json = executeJson(request(sessionUrl(sessionId)).delete().build())
        return AsrServiceFinalResult(
            text = json.optString("text"),
            error = optionalError(json)
        )
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .apply {
            if (bearerToken.isNotBlank()) {
                header("Authorization", "Bearer $bearerToken")
            }
        }

    private fun sessionUrl(sessionId: String, child: String? = null): HttpUrl {
        require(sessionId.isNotBlank()) { "sessionId 不能为空" }
        return sessionsUrl.newBuilder()
            .addPathSegment(sessionId)
            .apply { if (child != null) addPathSegment(child) }
            .build()
    }

    private fun executeJson(request: Request): JSONObject {
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
                throw IOException("ASR 服务请求失败：$message")
            }
            if (body.isBlank()) JSONObject() else try {
                JSONObject(body)
            } catch (_: Exception) {
                throw IOException("ASR 服务返回了无效 JSON")
            }
        }
    }

    private fun optionalError(json: JSONObject): String? {
        val value = json.opt("error")
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> value.optString("message").trim().takeIf { it.isNotEmpty() }
            else -> value.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PCM_MEDIA_TYPE = "application/octet-stream".toMediaType()

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}

data class AsrServiceHealth(
    val ok: Boolean,
    val live: Boolean,
    val version: String
)
