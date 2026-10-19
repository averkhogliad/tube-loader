package io.averkhogliad.tubeloader.tools.smoke

import java.io.PrintStream

private const val SAVE_CURSOR = "\u001B7"
private const val RESTORE_CURSOR = "\u001B8"
private const val ERASE_DOWN = "\u001B[J"
private const val NEWLINE = "\r\n"

internal class ProgressDashboard(private val out: PrintStream = System.out) {
    private val lock = Any()
    private val lines = LinkedHashMap<String, String>()
    private var blockOpen = false

    fun register(key: String, text: String) {
        synchronized(lock) {
            lines[key] = text
            refresh()
        }
    }

    fun update(key: String, text: String) {
        synchronized(lock) {
            if (text.isEmpty() || lines[key] == text) return
            lines[key] = text
            refresh()
        }
    }

    fun complete(key: String, terminalLine: String) {
        synchronized(lock) {
            lines.remove(key)
            note(terminalLine)
        }
    }

    /** Prints a permanent line above the live block and redraws the block below it. */
    fun note(text: String) {
        synchronized(lock) {
            closeBlock()
            out.print(text)
            out.print(NEWLINE)
            drawBlock()
        }
    }

    fun close() {
        synchronized(lock) {
            closeBlock()
            out.flush()
        }
    }

    private fun refresh() {
        closeBlock()
        drawBlock()
    }

    /**
     * Paints the block at the saved row and saves that row again. Wiping everything below the saved
     * row before repainting is what keeps a line the terminal wrapped on its own from leaving a
     * leftover row: the height of the block is never counted, so it never has to be guessed.
     */
    private fun drawBlock() {
        if (lines.isEmpty()) return
        out.print(SAVE_CURSOR)
        lines.values.forEach { text ->
            out.print(text)
            out.print(NEWLINE)
        }
        blockOpen = true
        out.flush()
    }

    /** Wipes the block and returns the cursor to its first row. */
    private fun closeBlock() {
        if (!blockOpen) return
        out.print(RESTORE_CURSOR)
        out.print(ERASE_DOWN)
        blockOpen = false
    }
}
