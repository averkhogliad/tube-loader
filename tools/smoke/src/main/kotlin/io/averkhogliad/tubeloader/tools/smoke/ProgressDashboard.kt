package io.averkhogliad.tubeloader.tools.smoke

import java.io.PrintStream

private const val SAVE_CURSOR = "\u001B7"
private const val RESTORE_CURSOR = "\u001B8"
private const val ERASE_DOWN = "\u001B[J"
private const val NEWLINE = "\r\n"

/**
 * The live table of the run: one row per planned target, redrawn in place while the downloads go.
 *
 * Terminal rows stay in the table: the table itself is the report of the run, so a finished target
 * is updated in its own row instead of being printed as a line of its own. Only a note — a failure
 * of the run as a whole — is printed above the table and stays there forever.
 */
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

    /** Prints a permanent line above the live table and redraws the table below it. */
    fun note(text: String) {
        synchronized(lock) {
            closeBlock()
            out.print(text)
            out.print(NEWLINE)
            drawBlock()
        }
    }

    /** Stops drawing and hands the terminal over: the table stays on the screen as the report. */
    fun close() {
        synchronized(lock) {
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
