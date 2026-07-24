package com.kingzcheung.xime.speech.doutype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouTypeProtoTest {
    @Test
    fun varint_encodes_small_and_large_values() {
        assertTrue(DouTypeProto.varint(0).contentEquals(byteArrayOf(0)))
        assertTrue(DouTypeProto.varint(127).contentEquals(byteArrayOf(127)))
        assertTrue(DouTypeProto.varint(128).contentEquals(byteArrayOf(0x80.toByte(), 0x01)))
    }

    @Test
    fun round_trip_handshake_fields() {
        val payload = DouTypeProto.concat(
            DouTypeProto.str(1, "token-abc"),
            DouTypeProto.str(2, "OrnqKvSSrs"),
            DouTypeProto.str(3, "ASR"),
            DouTypeProto.str(4, "v2"),
            DouTypeProto.str(5, "StartTask"),
            DouTypeProto.str(7, "11111111-1111-1111-1111-111111111111")
        )
        val fields = DouTypeProto.parseFields(payload)
        assertEquals("token-abc", fields[1])
        assertEquals("OrnqKvSSrs", fields[2])
        assertEquals("ASR", fields[3])
        assertEquals("v2", fields[4])
        assertEquals("StartTask", fields[5])
        assertEquals("11111111-1111-1111-1111-111111111111", fields[7])
    }

    @Test
    fun audio_envelope_contains_pcm_and_frame_state() {
        val pcm = ByteArray(640) { 0x11 }
        val frame = DouTypeProto.concat(
            DouTypeProto.str(3, "ASR"),
            DouTypeProto.str(5, "TaskRequest"),
            DouTypeProto.str(6, """{"extra":{},"timestamp_ms":0}"""),
            DouTypeProto.bytes(7, pcm),
            DouTypeProto.str(8, "task"),
            DouTypeProto.integer(9, 1)
        )
        val fields = DouTypeProto.parseFields(frame)
        assertEquals("TaskRequest", fields[5])
        assertEquals(1L, fields[9])
        val audio = fields[7]
        assertTrue(audio is ByteArray || (audio is String && audio.isNotEmpty()))
        if (audio is ByteArray) {
            assertEquals(640, audio.size)
            assertEquals(0x11.toByte(), audio[0])
        }
    }
}
