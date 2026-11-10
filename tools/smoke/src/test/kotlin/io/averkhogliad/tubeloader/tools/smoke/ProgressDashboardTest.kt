package io.averkhogliad.tubeloader.tools.smoke

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ProgressDashboardTest :
    FreeSpec({

        lateinit var screen: Screen

        beforeEach { screen = Screen() }

        afterEach { screen.close() }

        "live block" - {
            "renders one row per target, in registration order" {
                screen.dashboard.register("a", "first    resolving")
                screen.dashboard.register("b", "second   resolving")
                screen.dashboard.update("a", "first    downloading 10%")
                screen.dashboard.update("b", "second   downloading 20%")

                screen.lines() shouldBe listOf("first    downloading 10%", "second   downloading 20%")
            }

            "rewrites a row in place instead of appending" {
                screen.dashboard.register("a", "a  downloading 10%")
                screen.dashboard.update("a", "a  downloading 20%")
                screen.dashboard.update("a", "a  downloading 30%")

                screen.lines() shouldBe listOf("a  downloading 30%")
            }

            "ignores an empty update" {
                screen.dashboard.register("a", "a  resolving")
                screen.dashboard.update("a", "")

                screen.lines() shouldBe listOf("a  resolving")
            }

            "keeps a row longer than the terminal instead of truncating it" {
                screen.width = NARROW
                val text = "a  downloading 55% of the file"
                screen.dashboard.register("a", text)

                screen.lines() shouldBe text.chunked(NARROW)
            }

            "does not leave a stale row when a wrapped row is updated" {
                screen.width = NARROW
                screen.dashboard.register("a", "a  downloading 10% of the file")
                screen.dashboard.update("a", "a  downloading 55% of the file")
                screen.dashboard.update("a", "a  downloading 80% of the file")

                screen.lines() shouldBe "a  downloading 80% of the file".chunked(NARROW)
            }

            "does not drift while a wrapped row grows" {
                screen.width = NARROW
                screen.dashboard.register("a", "a  1")

                repeat(5) { step -> screen.dashboard.update("a", "a  ${"x".repeat(step + 1)}") }

                screen.lines() shouldBe "a  xxxxx".chunked(NARROW)
            }

            "does not drift while a wrapped row shrinks" {
                screen.width = NARROW
                screen.dashboard.register("a", "a  ${"x".repeat(40)}")

                repeat(5) { step -> screen.dashboard.update("a", "a  ${"x".repeat(40 - step * 5)}") }

                screen.lines() shouldBe "a  ${"x".repeat(20)}".chunked(NARROW)
            }

            "keeps a second row below a wrapped first one" {
                screen.width = NARROW
                screen.dashboard.register("a", "a  ${"x".repeat(35)}")
                screen.dashboard.register("b", "b  downloading 20%")

                screen.lines() shouldBe "a  ${"x".repeat(35)}".chunked(NARROW) + "b  downloading 20%"
            }
        }

        "terminal rows" - {
            "stay in the table in the row of their own target" {
                screen.dashboard.register("a", "a  resolving")
                screen.dashboard.register("b", "b  resolving")
                screen.dashboard.update("a", "a  downloading 50%")
                screen.dashboard.update("a", "a  done (clip.mp4, 3s)")
                screen.dashboard.update("b", "b  downloading 80%")
                screen.dashboard.update("b", "b  done (other.mp4, 5s)")

                screen.lines() shouldBe listOf("a  done (clip.mp4, 3s)", "b  done (other.mp4, 5s)")
            }

            "stay whole when they wrap" {
                screen.width = NARROW
                val done = "a  done (clip.mp4, 3s)"
                screen.dashboard.register("a", "a  resolving")
                screen.dashboard.update("a", done)

                screen.lines() shouldBe done.chunked(NARROW)
            }
        }

        "close" - {
            "leaves the table on the screen as the report of the run" {
                screen.dashboard.register("a", "a  done (clip.mp4, 3s)")
                screen.dashboard.close()

                screen.lines() shouldBe listOf("a  done (clip.mp4, 3s)")
            }
        }

        "note" - {
            "prints a permanent line above the live table" {
                screen.dashboard.register("a", "a  downloading 10%")
                screen.dashboard.note("ffmpeg exited with code 1")

                screen.lines() shouldBe listOf("ffmpeg exited with code 1", "a  downloading 10%")
            }

            "keeps a wrapped note above the live table" {
                screen.width = NARROW
                val note = "ffmpeg exited with code 1: noisy failure"
                screen.dashboard.register("a", "a  working")
                screen.dashboard.note(note)

                screen.lines() shouldBe note.chunked(NARROW) + "a  working"
            }
        }
    })
