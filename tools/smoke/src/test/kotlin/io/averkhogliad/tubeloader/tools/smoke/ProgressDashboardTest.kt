package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream

private const val WIDE = 200
private const val NARROW = 20
private const val ESCAPE = '\u001B'

class ProgressDashboardTest :
    FreeSpec({

        "live block" - {
            "renders one line per download, in registration order" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "first: resolving")
                dashboard.register("b", "second: resolving")
                dashboard.update("a", "first: downloading 10%")
                dashboard.update("b", "second: downloading 20%")

                screen.lines() shouldBe listOf("first: downloading 10%", "second: downloading 20%")
            }

            "rewrites a line in place instead of appending" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: downloading 10%")
                dashboard.update("a", "a: downloading 20%")
                dashboard.update("a", "a: downloading 30%")

                screen.lines() shouldBe listOf("a: downloading 30%")
            }

            "ignores an empty progress update" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: resolving")
                dashboard.update("a", "")

                screen.lines() shouldBe listOf("a: resolving")
            }

            "keeps a line longer than the terminal instead of truncating it" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                val text = "a: downloading 55% of the file"

                dashboard.register("a", text)

                screen.lines() shouldBe text.chunked(NARROW)
            }

            "does not leave a stale row when a wrapped line is updated" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: downloading 10% of the file")
                dashboard.update("a", "a: downloading 55% of the file")
                dashboard.update("a", "a: downloading 80% of the file")

                screen.lines() shouldBe "a: downloading 80% of the file".chunked(NARROW)
            }

            "does not drift while a wrapped line grows" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: 1")

                repeat(5) { step -> dashboard.update("a", "a: ${"x".repeat(step + 1)}") }

                screen.lines() shouldBe "a: xxxxx".chunked(NARROW)
            }

            "does not drift while a wrapped line shrinks" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: ${"x".repeat(40)}")

                repeat(5) { step -> dashboard.update("a", "a: ${"x".repeat(40 - step * 5)}") }

                screen.lines() shouldBe "a: ${"x".repeat(20)}".chunked(NARROW)
            }

            "keeps a second download below a wrapped first one" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: ${"x".repeat(35)}")
                dashboard.register("b", "b: downloading 20%")

                screen.lines() shouldBe "a: ${"x".repeat(35)}".chunked(NARROW) + "b: downloading 20%"
            }

            "close clears the live block" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: downloading 10%")
                dashboard.close()

                screen.lines() shouldBe emptyList()
            }
        }

        "terminal lines" - {
            "stay above the live progress" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: resolving")
                dashboard.register("b", "b: resolving")
                dashboard.update("a", "a: downloading 50%")
                dashboard.complete("a", "a: done (clip.mp4, 3s)")
                dashboard.update("b", "b: downloading 80%")
                dashboard.complete("b", "b: done (other.mp4, 5s)")

                screen.lines() shouldBe listOf("a: done (clip.mp4, 3s)", "b: done (other.mp4, 5s)")
            }

            "stay whole when they wrap" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                val done = "a: done (clip.mp4, 3s)"
                dashboard.register("a", "a: resolving")
                dashboard.complete("a", done)

                screen.lines() shouldBe done.chunked(NARROW)
            }

            "survive a close of the live block" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: resolving")
                dashboard.complete("a", "a: done")
                dashboard.close()

                screen.lines() shouldBe listOf("a: done")
            }
        }

        "note" - {
            "prints a permanent line above the live block" {
                val screen = Screen()
                val dashboard = ProgressDashboard(screen.out)
                dashboard.register("a", "a: downloading 10%")
                dashboard.note("ffmpeg exited with code 1")

                screen.lines() shouldBe listOf("ffmpeg exited with code 1", "a: downloading 10%")
            }

            "keeps a wrapped note above the live block" {
                val screen = Screen(NARROW)
                val dashboard = ProgressDashboard(screen.out)
                val note = "ffmpeg exited with code 1: noisy failure"
                dashboard.register("a", "a: working")
                dashboard.note(note)

                screen.lines() shouldBe note.chunked(NARROW) + "a: working"
            }
        }
    })

/**
 * A terminal that wraps a line longer than its [width], honours the cursor control the dashboard
 * emits, and lets a saved cursor row be restored. A dashboard that guesses the height of its block
 * instead of saving the cursor row will leave the tail of a wrapped line behind, and that shows up
 * here as a leftover row.
 */
private class Screen(val width: Int = WIDE) {
    private val buffer = ByteArrayOutputStream()
    val out = PrintStream(buffer, false, Charsets.UTF_8)

    fun lines(): List<String> = Terminal(buffer.toString(Charsets.UTF_8), width).render().dropLastWhile { it.isEmpty() }
}

private class Terminal(private val text: String, private val width: Int) {
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
