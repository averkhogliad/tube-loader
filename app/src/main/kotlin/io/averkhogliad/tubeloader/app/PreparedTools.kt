package io.averkhogliad.tubeloader.app

import java.nio.file.Path

/**
 * The state of one external tool after preparation.
 */
sealed interface ToolStatus {

    /** The tool is on the disk: [binary] is the file to run. */
    data class Ready(val binary: Path) : ToolStatus

    /** The tool cannot be obtained; [reason] is what the attempt to obtain it said, when it said anything. */
    data class Unavailable(val reason: String?) : ToolStatus
}

/**
 * The external tools of the application, each with its own status: one tool missing never hides
 * the state of the other, and a frontend shows every one of them on its own.
 */
data class PreparedTools(val ffmpeg: ToolStatus, val ytDlp: ToolStatus)
