package io.averkhogliad.tubeloader.core.download

import io.averkhogliad.tubeloader.core.domain.Quality
import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MediaMetaGens(
    val ids: Gen<String> = Arb.string(1..8),
    val titles: Gen<String> = Arb.string(1..16),
    val authors: Gen<String> = Arb.string(1..16),
    val durations: Gen<Duration> = Arb.long(0L..10_000L).map { it.seconds },
    val thumbnails: Gen<String?> = Arb.string(1..16).orNull(),
    val qualities: Gen<List<Quality>> = Arb.list(Arb.qualities(), 1..3),
)
