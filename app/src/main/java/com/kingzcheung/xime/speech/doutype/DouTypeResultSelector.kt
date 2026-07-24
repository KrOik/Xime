package com.kingzcheung.xime.speech.doutype

/**
 * Multipass 结果选择：对齐 Python `asr_ws.AsrSession.final_text` /
 * `_classify_pass`。优先更长文本，同长度时优先 offline > sentence > final > interim。
 * 绝不接受更短的 offline 重写覆盖已有长结果。
 */
internal object DouTypeResultSelector {
    const val RANK_OFFLINE = 5
    const val RANK_SENTENCE = 4
    const val RANK_FINAL = 3
    const val RANK_INTERIM = 1

    fun rank(
        isOffline: Boolean,
        streamDone: Boolean,
        vadDone: Boolean,
        wireInterim: Boolean
    ): Int = when {
        isOffline -> RANK_OFFLINE
        streamDone || vadDone -> RANK_SENTENCE
        !wireInterim -> RANK_FINAL
        else -> RANK_INTERIM
    }

    /**
     * @return 若 [candidate] 应替换 [current]，返回 true。
     */
    fun isBetter(
        current: String,
        currentRank: Int,
        candidate: String,
        candidateRank: Int
    ): Boolean {
        if (candidate.isBlank()) return false
        return when {
            current.isBlank() -> true
            candidate.length > current.length -> true
            candidate.length < current.length -> false
            candidateRank > currentRank -> true
            candidateRank == currentRank && candidate != current -> true
            else -> false
        }
    }
}
