package io.averkhogliad.tubeloader.tools.smoke

import java.io.ByteArrayOutputStream
import java.io.OutputStream

private const val NEWLINE = '\n'.code.toByte()

/**
 * Splits a byte stream into lines and hands each one to [sink]. The core reports its own diagnostics —
 * an ffmpeg failure with the tool's stderr, a missing binary — to `System.err`; left on the console
 * they are blanked by the next progress redraw, so the script routes them into the dashboard instead.
 */
internal class LineSinkStream(private val sink: (String) -> Unit) : OutputStream() {

    private val lock = Any()
    private val pending = ByteArrayOutputStream()

    override fun write(byte: Int) {
        write(byteArrayOf(byte.toByte()), offset = 0, length = 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            var start = offset
            for (index in offset until offset + length) {
                if (bytes[index] == NEWLINE) {
                    pending.write(bytes, start, index - start)
                    emit(pending.toByteArray())
                    pending.reset()
                    start = index + 1
                }
            }
            pending.write(bytes, start, offset + length - start)
        }
    }

    /** Emits what is left after the last newline of the stream. */
    fun drain() {
        synchronized(lock) {
            if (pending.size() > 0) {
                emit(pending.toByteArray())
                pending.reset()
            }
        }
    }

    private fun emit(bytes: ByteArray) {
        val line = bytes.decodeToString().trimEnd('\r')
        if (line.isNotBlank()) sink(line)
    }
}
