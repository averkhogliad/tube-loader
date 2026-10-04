package io.averkhogliad.tubeloader.core

import java.io.Closeable
import java.nio.file.Path

/**
 * Core port for operations on media files. Implementations translate an operation into whatever the
 * backing tool understands (bundled library or an external CLI process).
 *
 * Cancelling the coroutine an operation runs in interrupts it: the implementation kills the whole
 * process tree of the tool it started, orphaned children are not allowed.
 *
 * Closing is for what outlives a coroutine — an idle process, a warm pool. It is idempotent and
 * bounded; the owner of the tool calls it after the work has stopped.
 */
interface MediaTool : Closeable {
    /**
     * Joins a [videoTrack] and an [audioTrack] into a single file by copying both streams.
     */
    suspend fun mux(
        videoTrack: Path,
        audioTrack: Path,
        output: Path,
        onProgress: (Progress) -> Unit,
    ): Result<Unit>

    /**
     * Rewrites a single input into [output] by copying its streams, so the result is a normalized
     * container of the same content.
     */
    suspend fun remux(
        input: Path,
        output: Path,
        onProgress: (Progress) -> Unit,
    ): Result<Unit>
}
