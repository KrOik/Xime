package com.kingzcheung.xime.speech.service

data class AsrServiceEvent(
    val text: String,
    val type: String
)

data class AsrServicePollResult(
    val text: String = "",
    val events: List<AsrServiceEvent> = emptyList(),
    val error: String? = null
)

data class AsrServiceFinalResult(
    val text: String,
    val error: String? = null
)

/** `/v1/live/sessions` 的可替换客户端边界。 */
interface AsrServiceClient {
    fun createSession(): String
    fun pushAudio(sessionId: String, pcm: ByteArray)
    fun poll(sessionId: String): AsrServicePollResult
    fun finish(sessionId: String): AsrServiceFinalResult
}
