package io.averkhogliad.tubeloader.tools.smoke

import java.io.ByteArrayOutputStream
import java.io.PrintStream

internal const val WIDE = 200
internal const val NARROW = 20

private const val ESCAPE = '\u001B'

/**
 * A dashboard over a captured terminal: the case drives it, the flow method closes it.
 *
 * The terminal wraps a line longer than its [width], honours the cursor control the dashboard
 * emits, and lets a saved cursor row be restored. A dashboard that guesses the height of its block
 * instead of saving the cursor row will leave the tail of a wrapped line behind, and that shows up
 * here as a leftover row.
 */
internal class Screen : AutoCloseable {

    /** The width of the terminal the dashboard draws into: a narrow one wraps the lines it prints. */
    var width: Int = WIDE

    private val buffer = ByteArrayOutputStream()
    private val out = PrintStream(buffer, false, Charsets.UTF_8)

    val dashboard = ProgressDashboard(out)

    fun lines(): List<String> = Terminal(buffer.toString(Charsets.UTF_8), width).render().dropLastWhile { it.isEmpty() }

    override fun close() {
        dashboard.close()
        out.flush()
    }
}

internal class Terminal(private val text: String, private val width: Int) {

    private val rows = mutableListOf(StringBuilder())
    private var row = 0
    private var col = 0
    private var savedRow = 0

    fun render(): List<String> {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            index +=
                when {
                    char == ESCAPE -> {
                        escape(index)
                    }

                    char == '\r' -> {
                        col = 0
                        1
                    }

                    char == '\n' -> {
                        row += 1
                        1
                    }

                    else -> {
                        put(char)
                    }
                }
        }
        return rows.map(StringBuilder::toString)
    }

    private fun escape(index: Int): Int =
        when (text.getOrNull(index + 1)) {
            '7', 's' -> {
                savedRow = row
                2
            }

            '8', 'u' -> {
                row = savedRow
                col = 0
                2
            }

            '[' -> {
                control(index)
            }

            else -> {
                1
            }
        }

    private fun control(index: Int): Int {
        var scan = index + 2
        while (scan < text.length && (text[scan].isDigit() || text[scan] == ';')) scan++
        val parameter = text.substring(index + 2, scan)
        when (text.getOrNull(scan)) {
            'A' -> {
                row = (row - (parameter.toIntOrNull() ?: 1)).coerceAtLeast(0)
            }

            'B' -> {
                row += parameter.toIntOrNull() ?: 1
            }

            'J' -> {
                current().setLength(minOf(col, current().length))
                if (parameter == "2") {
                    savedRow = 0
                    row = 0
                    col = 0
                }
                while (rows.size > row + 1) rows.removeAt(rows.lastIndex)
            }
        }
        return scan + 1 - index
    }

    private fun put(char: Char): Int {
        if (col >= width) {
            row += 1
            col = 0
        }
        val line = current()
        while (line.length < col) line.append(' ')
        if (col < line.length) line.replace(col, col + 1, char.toString()) else line.append(char)
        col += 1
        return 1
    }

    private fun current(): StringBuilder {
        while (rows.size <= row) rows.add(StringBuilder())
        return rows[row]
    }
}
