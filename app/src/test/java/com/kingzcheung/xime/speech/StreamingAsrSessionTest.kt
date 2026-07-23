package com.kingzcheung.xime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAsrSessionTest {
    @Test
    fun `会话就绪后才接受识别结果`() {
        val session = StreamingAsrSession()

        assertFalse(session.accept(StreamingAsrEvent.Partial("过早结果")))
        assertTrue(session.start())
        assertFalse(session.accept(StreamingAsrEvent.Partial("仍未就绪")))
        assertTrue(session.accept(StreamingAsrEvent.SessionReady))
        assertTrue(session.accept(StreamingAsrEvent.Partial("你好")))
        assertEquals(StreamingAsrSessionState.LISTENING, session.state)
    }

    @Test
    fun `收尾期间仍接受partial但final只能接受一次`() {
        val session = readySession()

        assertTrue(session.finish())
        assertTrue(session.accept(StreamingAsrEvent.Partial("你好世")))
        assertTrue(session.accept(StreamingAsrEvent.Final("你好世界")))
        assertEquals(StreamingAsrSessionState.COMPLETED, session.state)

        assertFalse(session.accept(StreamingAsrEvent.Final("你好世界")))
        assertFalse(session.accept(StreamingAsrEvent.Partial("迟到结果")))
    }

    @Test
    fun `握手完成前松手仍接受ready并保持收尾状态`() {
        val session = StreamingAsrSession()

        assertTrue(session.start())
        assertTrue(session.finish())
        assertTrue(session.accept(StreamingAsrEvent.SessionReady))
        assertEquals(StreamingAsrSessionState.FINISHING, session.state)
        assertTrue(session.accept(StreamingAsrEvent.Final("短句")))
        assertEquals(StreamingAsrSessionState.COMPLETED, session.state)
    }

    @Test
    fun `取消后丢弃所有迟到回调`() {
        val session = readySession()

        assertTrue(session.cancel())
        assertEquals(StreamingAsrSessionState.CANCELLED, session.state)
        assertFalse(session.accept(StreamingAsrEvent.Partial("迟到中间结果")))
        assertFalse(session.accept(StreamingAsrEvent.Final("迟到最终结果")))
        assertFalse(session.accept(StreamingAsrEvent.Failure("迟到错误")))
    }

    @Test
    fun `没有final的远端关闭视为失败`() {
        val session = readySession()

        assertTrue(session.finish())
        assertTrue(session.accept(StreamingAsrEvent.RemoteClosed))
        assertEquals(StreamingAsrSessionState.FAILED, session.state)
    }

    @Test
    fun `完成后可开始下一会话`() {
        val session = readySession()
        assertTrue(session.accept(StreamingAsrEvent.Final("第一句")))

        assertTrue(session.start())
        assertEquals(StreamingAsrSessionState.CONNECTING, session.state)
        assertTrue(session.accept(StreamingAsrEvent.SessionReady))
        assertTrue(session.accept(StreamingAsrEvent.Final("第二句")))
    }

    @Test
    fun `空结果不进入上层`() {
        val session = readySession()

        assertFalse(session.accept(StreamingAsrEvent.Partial("  ")))
        assertFalse(session.accept(StreamingAsrEvent.Final("")))
        assertEquals(StreamingAsrSessionState.LISTENING, session.state)
    }

    private fun readySession() = StreamingAsrSession().apply {
        assertTrue(start())
        assertTrue(accept(StreamingAsrEvent.SessionReady))
    }
}
