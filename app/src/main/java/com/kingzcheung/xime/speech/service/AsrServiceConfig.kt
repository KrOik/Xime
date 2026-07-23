package com.kingzcheung.xime.speech.service

import java.net.URI

data class AsrServiceConfig(
    val baseUrl: String,
    val bearerToken: String = ""
)

object AsrServiceConfigValidator {
    fun normalizeHttpsUrl(input: String): String {
        val value = input.trim().trimEnd('/')
        require(value.isNotEmpty()) { "请输入 ASR 服务地址" }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("ASR 服务地址格式无效")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "ASR 服务必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "ASR 服务地址缺少有效主机名" }
        require(uri.userInfo == null) { "ASR 服务地址不能包含用户名或密码" }
        require(uri.query == null && uri.fragment == null) { "ASR 服务地址不能包含查询参数或片段" }
        return value
    }
}
