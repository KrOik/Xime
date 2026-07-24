package com.kingzcheung.xime.speech.doutype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouTypeResultSelectorTest {
    @Test
    fun rank_prefers_offline_over_stream_finish() {
        assertEquals(
            DouTypeResultSelector.RANK_OFFLINE,
            DouTypeResultSelector.rank(
                isOffline = true,
                streamDone = true,
                vadDone = false,
                wireInterim = false
            )
        )
        assertEquals(
            DouTypeResultSelector.RANK_SENTENCE,
            DouTypeResultSelector.rank(
                isOffline = false,
                streamDone = true,
                vadDone = false,
                wireInterim = true
            )
        )
        assertEquals(
            DouTypeResultSelector.RANK_INTERIM,
            DouTypeResultSelector.rank(
                isOffline = false,
                streamDone = false,
                vadDone = false,
                wireInterim = true
            )
        )
    }

    @Test
    fun isBetter_never_replaces_longer_text_with_shorter_offline() {
        assertFalse(
            DouTypeResultSelector.isBetter(
                current = "今天天气真不错我们一起出去玩吧",
                currentRank = DouTypeResultSelector.RANK_SENTENCE,
                candidate = "今天天气真不错",
                candidateRank = DouTypeResultSelector.RANK_OFFLINE
            )
        )
    }

    @Test
    fun isBetter_accepts_longer_offline_rewrite() {
        assertTrue(
            DouTypeResultSelector.isBetter(
                current = "今天天气真不错",
                currentRank = DouTypeResultSelector.RANK_SENTENCE,
                candidate = "今天天气真不错，我们一起出去玩吧。",
                candidateRank = DouTypeResultSelector.RANK_OFFLINE
            )
        )
    }

    @Test
    fun isBetter_same_length_prefers_higher_rank() {
        assertTrue(
            DouTypeResultSelector.isBetter(
                current = "做飞机",
                currentRank = DouTypeResultSelector.RANK_INTERIM,
                candidate = "坐飞机",
                candidateRank = DouTypeResultSelector.RANK_OFFLINE
            )
        )
    }
}
