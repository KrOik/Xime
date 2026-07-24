package com.kingzcheung.xime.speech.doutype

/** Protobuf-lite field codec used by DouType WebSocket wire format. */
object DouTypeProto {
    fun str(field: Int, value: String): ByteArray {
        val b = value.toByteArray(Charsets.UTF_8)
        return concat(byteArrayOf((field shl 3 or 2).toByte()), varint(b.size), b)
    }

    fun bytes(field: Int, value: ByteArray): ByteArray =
        concat(byteArrayOf((field shl 3 or 2).toByte()), varint(value.size), value)

    fun integer(field: Int, value: Int): ByteArray =
        concat(byteArrayOf((field shl 3).toByte()), varint(value))

    fun varint(v: Int): ByteArray {
        var n = v
        val out = ArrayList<Byte>()
        do {
            var b = n and 0x7F
            n = n ushr 7
            if (n != 0) b = b or 0x80
            out.add(b.toByte())
        } while (n != 0)
        return out.toByteArray()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun parseFields(data: ByteArray): Map<Int, Any> {
        val result = LinkedHashMap<Int, Any>()
        var i = 0
        while (i < data.size) {
            val tag = data[i++].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 7
            when (wire) {
                0 -> {
                    var value = 0L
                    var shift = 0
                    while (i < data.size) {
                        val b = data[i++].toInt() and 0xFF
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    result[field] = value
                }
                2 -> {
                    var len = 0
                    var shift = 0
                    while (i < data.size) {
                        val b = data[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    val end = (i + len).coerceAtMost(data.size)
                    val raw = data.copyOfRange(i, end)
                    i = end
                    val asString = runCatching {
                        val s = String(raw, Charsets.UTF_8)
                        // reject if replacement char present for binary payloads
                        if (s.toByteArray(Charsets.UTF_8).contentEquals(raw)) s else null
                    }.getOrNull()
                    result[field] = asString ?: raw
                }
                1 -> i = (i + 8).coerceAtMost(data.size)
                5 -> i = (i + 4).coerceAtMost(data.size)
                else -> return result
            }
        }
        return result
    }
}
