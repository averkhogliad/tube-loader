package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private const val NAME = "clip"
private val TASK = TaskId(0x1234_5678)
private val STARTED = Instant.fromEpochMilliseconds(1_700_000_000_000)

private fun row(name: String, width: Int = name.length): TargetRow =
    TargetRow(key = "0", name = name, fileName = "$name.mp4", width = width)

private fun state(
    status: DownloadStatus,
    progress: Progress = Progress.Indeterminate,
    finishedAt: Instant? = null,
): DownloadState = DownloadState(TASK, status, progress, STARTED, finishedAt)

class ProgressFormatterTest :
    FreeSpec({

        "nameWidth" - {
            "takes the longest name of the run" {
                nameWidth(listOf("a", "three", "ze")) shouldBe 5
            }

            "names no width when the run has no targets" {
                nameWidth(emptyList()) shouldBe 0
            }
        }

        "formatPlanned" - {
            "renders the row of a target that has not started yet" {
                formatPlanned(row(NAME)) shouldBe "clip  planned"
            }

            "pads the name to the width of the run" {
                formatPlanned(row(NAME, width = 6)) shouldBe "clip    planned"
            }
        }

        "formatSkipped" - {
            "names the reason of a target the run skipped" {
                formatSkipped(row(NAME), "no adapter claims the target") shouldBe
                    "clip  skipped: no adapter claims the target"
            }
        }

        "formatRow" - {
            "renders the waiting of a queued download" {
                formatRow(state(DownloadStatus.Queued), row(NAME)) shouldBe "clip  queued"
            }

            "renders the metadata phase" {
                formatRow(state(DownloadStatus.LoadingMeta), row(NAME)) shouldBe "clip  loading metadata"
            }

            "renders a determinate download as a percent" {
                formatRow(
                    state(DownloadStatus.Downloading, Progress.Absolute(processed = 4, total = 10)),
                    row(NAME),
                ) shouldBe "clip  downloading 40.00%"
            }

            "renders a third of the work with two decimals" {
                formatRow(
                    state(DownloadStatus.Downloading, Progress.Absolute(processed = 1, total = 3)),
                    row(NAME),
                ) shouldBe "clip  downloading 33.33%"
            }

            "renders an indeterminate download as a dash" {
                formatRow(state(DownloadStatus.Downloading, Progress.Indeterminate), row(NAME)) shouldBe
                    "clip  downloading -"
            }

            "renders the finalizing phase" {
                formatRow(state(DownloadStatus.Finalizing), row(NAME)) shouldBe "clip  finalizing"
            }

            "renders the cancelling phase" {
                formatRow(state(DownloadStatus.Cancelling), row(NAME)) shouldBe "clip  cancelling"
            }

            "renders a completed download with the file and the time it took" {
                formatRow(
                    state(DownloadStatus.Completed, finishedAt = Instant.fromEpochMilliseconds(1_700_000_010_000)),
                    row(NAME),
                ) shouldBe "clip  done (clip.mp4, 10s)"
            }

            "renders a cancelled download" {
                formatRow(state(DownloadStatus.Cancelled), row(NAME)) shouldBe "clip  cancelled"
            }

            "renders a failure with the error and the cause" {
                val cause = RuntimeException("server reset")
                formatRow(state(DownloadStatus.Failed(DownloadError.NetworkTransient, cause)), row(NAME)) shouldBe
                    "clip  failed: NetworkTransient: server reset"
            }

            "renders a failure without a cause" {
                formatRow(state(DownloadStatus.Failed(DownloadError.ExtractorBroken)), row(NAME)) shouldBe
                    "clip  failed: ExtractorBroken"
            }
        }
    })
