package com.kingzcheung.xime.speech

/**
 * 在线流式 ASR 的供应商无关会话状态。
 *
 * [RecognitionState] 用于界面展示；本状态机用于约束网络协议生命周期，避免将
 * partial、final、用户收尾和连接关闭混为一谈。
 */
enum class StreamingAsrSessionState {
    IDLE,
    CONNECTING,
    LISTENING,
    FINISHING,
    COMPLETED,
    CANCELLED,
    FAILED
}

sealed interface StreamingAsrEvent {
    data object SessionReady : StreamingAsrEvent
    data class Partial(val text: String) : StreamingAsrEvent
    data class Final(val text: String) : StreamingAsrEvent
    data class Failure(val message: String) : StreamingAsrEvent
    data object RemoteClosed : StreamingAsrEvent
}

/**
 * 校验异步 WebSocket 回调是否仍属于当前有效会话。
 *
 * 传输实现只有在 [accept] 返回 `true` 时才可向上层分发对应事件。
 */
class StreamingAsrSession {
    var state: StreamingAsrSessionState = StreamingAsrSessionState.IDLE
        private set

    @Synchronized
    fun start(): Boolean {
        if (state.isActive) return false
        state = StreamingAsrSessionState.CONNECTING
        return true
    }

    @Synchronized
    fun finish(): Boolean {
        if (state != StreamingAsrSessionState.CONNECTING &&
            state != StreamingAsrSessionState.LISTENING
        ) {
            return false
        }
        state = StreamingAsrSessionState.FINISHING
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (!state.isActive) return false
        state = StreamingAsrSessionState.CANCELLED
        return true
    }

    @Synchronized
    fun accept(event: StreamingAsrEvent): Boolean {
        return when (event) {
            StreamingAsrEvent.SessionReady -> {
                when (state) {
                    StreamingAsrSessionState.CONNECTING -> {
                        state = StreamingAsrSessionState.LISTENING
                        true
                    }

                    // 用户可能在握手完成前已经松手。传输层仍需接收 ready，
                    // 随后立即发送结束帧；状态保持 FINISHING。
                    StreamingAsrSessionState.FINISHING -> true
                    else -> false
                }
            }

            is StreamingAsrEvent.Partial -> {
                event.text.isNotBlank() &&
                    (state == StreamingAsrSessionState.LISTENING ||
                        state == StreamingAsrSessionState.FINISHING)
            }

            is StreamingAsrEvent.Final -> {
                if (event.text.isBlank() ||
                    (state != StreamingAsrSessionState.LISTENING &&
                        state != StreamingAsrSessionState.FINISHING)
                ) {
                    return false
                }
                state = StreamingAsrSessionState.COMPLETED
                true
            }

            is StreamingAsrEvent.Failure -> {
                if (!state.isActive) return false
                state = StreamingAsrSessionState.FAILED
                true
            }

            StreamingAsrEvent.RemoteClosed -> {
                if (!state.isActive) return false
                state = StreamingAsrSessionState.FAILED
                true
            }
        }
    }

    private val StreamingAsrSessionState.isActive: Boolean
        get() = this == StreamingAsrSessionState.CONNECTING ||
            this == StreamingAsrSessionState.LISTENING ||
            this == StreamingAsrSessionState.FINISHING
}
