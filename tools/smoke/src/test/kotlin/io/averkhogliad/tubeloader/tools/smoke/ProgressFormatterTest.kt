package io.averkhogliad.tubeloader.tools.smoke

import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.domain.TaskId
import io.averkhogliad.tubeloader.core.download.DownloadState
import io.averkhogliad.tubeloader.core.download.DownloadStatus
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private const val NAME = "Почему хороший продукт"
private val TASK = TaskId(0x1234_5678)
private val STARTED = Instant.fromEpochMilliseconds(1_700_000_000_000)

private fun state(
    status: DownloadStatus,
    progress: Progress = Progress.Indeterminate,
    finishedAt: Instant? = null,
): DownloadState = DownloadState(TASK, status, progress, STARTED, finishedAt)

class ProgressFormatterTest :
    FreeSpec({

        "formatProgress" - {
            "drops the indeterminate LoadingMeta" {
                formatProgress(state(DownloadStatus.LoadingMeta), NAME) shouldBe ""
            }

            "drops the indeterminate Finalizing" {
                formatProgress(state(DownloadStatus.Finalizing), NAME) shouldBe ""
            }

            "renders a determinate Downloading as a percent" {
                val out =
                    formatProgress(
                        state(DownloadStatus.Downloading, Progress.Absolute(processed = 4, total = 10)),
                        NAME,
                    )
                out shouldBe "[12345678] $NAME: downloading 40.00%"
            }

            "renders a third of the work with two decimals" {
                val out =
                    formatProgress(
                        state(DownloadStatus.Downloading, Progress.Absolute(processed = 1, total = 3)),
                        NAME,
                    )
                out shouldBe "[12345678] $NAME: downloading 33.33%"
            }

            "renders an indeterminate Downloading as a dash" {
                val out = formatProgress(state(DownloadStatus.Downloading, Progress.Indeterminate), NAME)
                out shouldBe "[12345678] $NAME: downloading -"
            }

            "renders a Queued once" {
                val out = formatProgress(state(DownloadStatus.Queued), NAME)
                out shouldBe "[12345678] $NAME: queued"
            }
        }

        "formatTerminal" - {
            "renders a Completed with the target path" {
                formatTerminal(
                    state(DownloadStatus.Completed, finishedAt = Instant.fromEpochMilliseconds(1_700_000_010_000)),
                    NAME,
                    targetName = "clip.mp4",
                ) shouldBe "[12345678] $NAME: done (clip.mp4, 10s)"
            }

            "renders a Cancelled" {
                formatTerminal(state(DownloadStatus.Cancelled), NAME) shouldBe "[12345678] $NAME: cancelled"
            }

            "renders a Failed with the error and cause" {
                val cause = RuntimeException("server reset")
                formatTerminal(state(DownloadStatus.Failed(DownloadError.NetworkTransient, cause)), NAME) shouldBe
                    "[12345678] $NAME: FAILED NetworkTransient: server reset"
            }

            "renders a Failed without a cause" {
                formatTerminal(state(DownloadStatus.Failed(DownloadError.ExtractorBroken)), NAME) shouldBe
                    "[12345678] $NAME: FAILED ExtractorBroken"
            }
        }
    })
