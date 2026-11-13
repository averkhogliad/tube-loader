package io.averkhogliad.tubeloader.core.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ProgressTest :
    FreeSpec({

        "fraction" - {
            "returns null for an indeterminate progress" {
                // given
                val progress: Progress = Progress.Indeterminate

                // when
                val actual = progress.fraction()

                // then
                actual shouldBe null
            }

            "returns null when the total is not positive" {
                // given
                val progresses = listOf(Progress.Absolute(0, 0), Progress.Absolute(1, -1))

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(null, null)
            }

            "returns null when the processed value is outside the measure" {
                // given
                val progresses = listOf(Progress.Absolute(-1, 10), Progress.Absolute(11, 10))

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(null, null)
            }

            "returns the share of an absolute progress inside the measure" {
                // given
                val progress = Progress.Absolute(5, 10)

                // when
                val actual = progress.fraction()

                // then
                actual shouldBe 0.5
            }

            "returns the boundaries of an absolute progress" {
                // given
                val progresses = listOf(Progress.Absolute(0, 10), Progress.Absolute(10, 10))

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(0.0, 1.0)
            }

            "returns null for a fraction that is not finite" {
                // given
                val progresses =
                    listOf(
                        Progress.Fraction(Double.NaN),
                        Progress.Fraction(Double.POSITIVE_INFINITY),
                        Progress.Fraction(Double.NEGATIVE_INFINITY),
                    )

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(null, null, null)
            }

            "returns null for a fraction outside its measure" {
                // given
                val progresses = listOf(Progress.Fraction(-0.1), Progress.Fraction(1.1))

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(null, null)
            }

            "returns the share of a fraction inside its measure" {
                // given
                val progress = Progress.Fraction(0.25)

                // when
                val actual = progress.fraction()

                // then
                actual shouldBe 0.25
            }

            "returns the boundaries of a fraction" {
                // given
                val progresses = listOf(Progress.Fraction(0.0), Progress.Fraction(1.0))

                // when
                val actual = progresses.map { it.fraction() }

                // then
                actual shouldBe listOf(0.0, 1.0)
            }
        }
    })
