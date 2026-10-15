package com.example.sample.utils

/**
 * Some analyzers send every segment in its own MLLP frame (MSH, PID, PV1, OBR, OBX, ... each wrapped
 * in <SB>...<EB><CR>). This joins those frames back into one message, so HL7Parser.parse() sees a
 * whole message and you send ONE ACK per message instead of one per segment.
 *
 * There is no end-of-message marker, so a message is complete when:
 *   - the next frame starts with MSH, or
 *   - the connection has been idle for a short time (call flush()), or it disconnects.
 */
class HL7MessageAssembler {
    private val segments = mutableListOf<String>()

    /**
     * Feed one MLLP frame (payload between SB and EB).
     * Returns the previous complete message when this frame starts a new one, otherwise null.
     */
    @Synchronized
    fun onFrame(frame: String): String? {
        val text = frame.trim { it == '\u000B' || it == '\u001C' || it.isWhitespace() }
        if (text.isEmpty()) return null
        if (text.startsWith("MSH")) {
            val completed = flush()
            segments += text
            return completed
        }
        if (segments.isEmpty()) return null   // segment with no MSH before it: drop it
        segments += text
        return null
    }

    /** Returns the buffered message and clears the buffer, or null if nothing is buffered. */
    @Synchronized
    fun flush(): String? {
        if (segments.isEmpty()) return null
        val message = segments.joinToString("\r")
        segments.clear()
        return message
    }
}