package io.averkhogliad.tubeloader.adapters.rutube

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PlayOptions(
    @SerialName("video_id") val videoId: String? = null,
    val title: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val duration: Long? = null,
    val author: Author? = null,
    @SerialName("video_balancer") val videoBalancer: VideoBalancer? = null,
)

@Serializable
internal data class Author(val name: String? = null)

@Serializable
internal data class VideoBalancer(
    @SerialName("default") val fallback: String? = null,
    val m3u8: String? = null,
)

@Serializable
internal data class ErrorDetail(val detail: Reason? = null)

@Serializable
internal data class Reason(val name: String? = null)
