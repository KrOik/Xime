package com.kingzcheung.xime.speech.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AsrServiceConfigTest {
    @Test
    fun `规范化HTTPS地址`() {
        assertEquals(
            "https://asr.example.com/base",
            AsrServiceConfigValidator.normalizeHttpsUrl("  https://asr.example.com/base///  ")
        )
    }

    @Test
    fun `拒绝空地址和明文HTTP`() {
        assertEquals(
            "请输入 ASR 服务地址",
            assertThrows(IllegalArgumentException::class.java) {
                AsrServiceConfigValidator.normalizeHttpsUrl(" ")
            }.message
        )
        assertEquals(
            "ASR 服务必须使用 HTTPS",
            assertThrows(IllegalArgumentException::class.java) {
                AsrServiceConfigValidator.normalizeHttpsUrl("http://asr.example.com")
            }.message
        )
    }

    @Test
    fun `拒绝凭据查询参数和片段`() {
        listOf(
            "https://user:pass@asr.example.com" to "ASR 服务地址不能包含用户名或密码",
            "https://asr.example.com?token=secret" to "ASR 服务地址不能包含查询参数或片段",
            "https://asr.example.com/#debug" to "ASR 服务地址不能包含查询参数或片段"
        ).forEach { (url, message) ->
            assertEquals(
                message,
                assertThrows(IllegalArgumentException::class.java) {
                    AsrServiceConfigValidator.normalizeHttpsUrl(url)
                }.message
            )
        }
    }
}
