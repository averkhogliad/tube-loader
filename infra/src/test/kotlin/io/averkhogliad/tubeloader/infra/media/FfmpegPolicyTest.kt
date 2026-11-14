package io.averkhogliad.tubeloader.infra.media

import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import java.io.IOException

class FfmpegPolicyTest :
    FreeSpec({

        "ffmpegPolicy" - {
            "names the Windows archive of the official build with its sidecar checksum" {
                // when
                val actual = ffmpegPolicy("Windows 11")

                // then
                actual.tool shouldBe "ffmpeg"
                actual.source.toString() shouldContain "ffmpeg-"
                actual.followRedirects shouldBe false
                actual.unpack shouldBe Unpack.ZipArchive
                val checksum = actual.checksum as ChecksumSource.Sidecar
                checksum.source.toString() shouldEndWith ".sha256"
            }

            "reports a platform without a configured build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { ffmpegPolicy("Linux") }

                // then
                failure.message!! shouldContain "ffmpeg"
                failure.message!! shouldContain "LINUX"
            }

            "reports every platform it has no build for" {
                // when + then
                listOf("Mac OS X", "FreeBSD").forEach { osName ->
                    shouldThrow<IOException> { ffmpegPolicy(osName) }
                }
            }
        }
    })
