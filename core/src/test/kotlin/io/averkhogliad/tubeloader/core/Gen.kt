package io.averkhogliad.tubeloader.core

import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string

fun Arb.Companion.qualities(
    ids: Gen<String> = Arb.string(1..8),
    kinds: Gen<TrackKind> = Arb.enum(),
    labels: Gen<String> = Arb.string(1..12),
): Arb<Quality> = Arb.bind(ids, kinds, labels, ::Quality)

fun Arb.Companion.mediaMetas(fieldGens: MediaMetaGens = MediaMetaGens()): Arb<MediaMeta> = Arb.bind(
    fieldGens.ids,
    fieldGens.titles,
    fieldGens.authors,
    fieldGens.durations,
    fieldGens.thumbnails,
    fieldGens.qualities,
    ::MediaMeta,
)

fun Arb.Companion.absoluteProgresses(totals: Arb<Long> = Arb.long(1L..1_000_000L)): Arb<SourceProgress.Absolute> =
    totals.flatMap { total ->
        Arb.long(0L..total).map { processed -> SourceProgress.Absolute(processed, total) }
    }

fun Arb.Companion.fractions(ratios: Arb<Double> = Arb.double(0.0..1.0)): Arb<SourceProgress.Fraction> =
    ratios.map { SourceProgress.Fraction(it) }
