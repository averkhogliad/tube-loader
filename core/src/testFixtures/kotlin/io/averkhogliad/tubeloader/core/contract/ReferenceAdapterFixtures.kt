package io.averkhogliad.tubeloader.core.contract

import io.averkhogliad.tubeloader.core.adapter.DownloadResult
import io.averkhogliad.tubeloader.core.adapter.LoadMetaResult
import io.averkhogliad.tubeloader.core.adapter.ResolveIdResult
import io.averkhogliad.tubeloader.core.domain.DownloadError
import io.averkhogliad.tubeloader.core.domain.MediaMeta
import io.averkhogliad.tubeloader.core.domain.Quality
import io.averkhogliad.tubeloader.core.domain.TrackKind
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

private const val REFERENCE_STREAM_URL = "https://cdn.reference.example/stream/ref-1/hls-720"

/**
 * Golden fixtures of the reference source: the recording under `golden/reference/` plus the meta and
 * qualities its parser must produce. The reference source exists so the contract suite can be proven
 * runnable — a future adapter plugs into the same suite with its own recording.
 *
 * The expected stream bytes are read back from the recording itself, so the golden file stays the
 * single source of truth for the payload.
 */
fun referenceAdapterFixtures(): SourceAdapterFixtures {
    val responses = recordedResponsesOf("reference")
    return SourceAdapterFixtures(
        responses = responses,
        resolveId =
            listOf(
                ResolveIdCase(
                    name = "returns the id for a url of the source",
                    input = "https://reference.example/watch?v=ref-1",
                    expected = ResolveIdResult.Found("ref-1"),
                ),
                ResolveIdCase(
                    name = "returns Unsupported for a url of another host",
                    input = "https://elsewhere.example/watch?v=ref-1",
                    expected = ResolveIdResult.Unsupported,
                ),
                ResolveIdCase(
                    name = "returns NotFound for a url of the source without an id",
                    input = "https://reference.example/watch",
                    expected = ResolveIdResult.NotFound,
                ),
                ResolveIdCase(
                    name = "returns NotFound for a url of the source with an empty id",
                    input = "https://reference.example/watch?v=",
                    expected = ResolveIdResult.NotFound,
                ),
            ),
        meta =
            listOf(
                MetaCase(
                    name = "parses the recorded meta into MediaMeta and qualities",
                    id = "ref-1",
                    expected =
                        LoadMetaResult.Found(
                            MediaMeta(
                                id = "ref-1",
                                title = "Reference clip",
                                author = "Reference author",
                                duration = 93.seconds,
                                thumbnailUrl = "https://cdn.reference.example/meta/ref-1.jpg",
                                qualities =
                                    listOf(
                                        Quality("hls-360", TrackKind.Video, "360p"),
                                        Quality("hls-720", TrackKind.Video, "720p"),
                                    ),
                            ),
                        ),
                ),
                MetaCase(
                    name = "returns NotFound for media that has no record",
                    id = "ref-absent",
                    expected = LoadMetaResult.NotFound,
                ),
                MetaCase(
                    name = "carries the transport failure of the source as the cause",
                    id = "ref-unreachable",
                    expected =
                        LoadMetaResult.Failed(
                            DownloadError.NetworkTransient,
                            IOException("no response from https://api.reference.example/video/ref-unreachable"),
                        ),
                ),
            ),
        download =
            listOf(
                DownloadCase(
                    name = "writes the recorded stream of the chosen quality",
                    id = "ref-1",
                    quality = Quality("hls-720", TrackKind.Video, "720p"),
                    expected = DownloadResult.Success,
                    expectedContent = responses.bodyFor(REFERENCE_STREAM_URL),
                ),
                DownloadCase(
                    name = "returns NetworkTransient when the recorded response is unreachable",
                    id = "ref-2",
                    quality = Quality("hls-720", TrackKind.Video, "720p"),
                    expected =
                        DownloadResult.Failed(
                            DownloadError.NetworkTransient,
                            IOException("no response from https://cdn.reference.example/stream/ref-2/hls-720"),
                        ),
                ),
                DownloadCase(
                    name = "returns NotFound when the recording has no stream for the quality",
                    id = "ref-1",
                    quality = Quality("hls-1080", TrackKind.Video, "1080p"),
                    expected =
                        DownloadResult.Failed(
                            DownloadError.NotFound,
                            IllegalArgumentException(
                                "no recorded response for " +
                                    "https://cdn.reference.example/stream/ref-1/hls-1080",
                            ),
                        ),
                ),
            ),
    )
}
