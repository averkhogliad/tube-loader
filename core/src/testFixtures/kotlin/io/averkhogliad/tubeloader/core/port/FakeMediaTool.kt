package io.averkhogliad.tubeloader.core.port

import io.averkhogliad.tubeloader.core.domain.Progress
import io.averkhogliad.tubeloader.core.port.MediaTool
import java.nio.file.Path

data class MuxCall(
    val videoTrack: Path,
    val audioTrack: Path,
    val output: Path,
)

data class RemuxCall(val input: Path, val output: Path)

class FakeMediaTool : MediaTool {

    var onMux: suspend (Path, Path, Path) -> Result<Unit> = { _, _, _ -> Result.success(Unit) }
    var onRemux: suspend (Path, Path) -> Result<Unit> = { _, _ -> Result.success(Unit) }

    var progress: Progress? = null

    val muxCalls = mutableListOf<MuxCall>()
    val remuxCalls = mutableListOf<RemuxCall>()

    override fun close() = Unit

    override suspend fun mux(
        videoTrack: Path,
        audioTrack: Path,
        output: Path,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> {
        muxCalls += MuxCall(videoTrack, audioTrack, output)
        progress?.let(onProgress)
        return onMux(videoTrack, audioTrack, output)
    }

    override suspend fun remux(input: Path, output: Path, onProgress: (Progress) -> Unit): Result<Unit> {
        remuxCalls += RemuxCall(input, output)
        progress?.let(onProgress)
        return onRemux(input, output)
    }
}
