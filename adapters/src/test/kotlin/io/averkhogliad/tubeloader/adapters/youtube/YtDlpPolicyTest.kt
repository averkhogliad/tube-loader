package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.infra.binaries.ChecksumSource
import io.averkhogliad.tubeloader.infra.binaries.Unpack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException

class YtDlpPolicyTest :
    FreeSpec({

        "ytDlpPolicy" - {
            "names the Windows asset of the latest release with the multi-asset checksum" {
                // when
                val actual = ytDlpPolicy("Windows 11")

                // then
                actual.tool shouldBe "yt-dlp"
                actual.source.toString() shouldContain "yt-dlp.exe"
                actual.followRedirects shouldBe true
                actual.unpack shouldBe Unpack.ReadyFile
                val checksum = actual.checksum as ChecksumSource.MultiAsset
                checksum.asset shouldBe "yt-dlp.exe"
                checksum.source.toString() shouldContain "SHA2-256SUMS"
            }

            "reports a platform without a configured build as unavailable" {
                // when
                val failure = shouldThrow<IOException> { ytDlpPolicy("Linux") }

                // then
                failure.message!! shouldContain "yt-dlp"
                failure.message!! shouldContain "LINUX"
            }

            "reports every platform it has no build for" {
                // when + then
                listOf("Mac OS X", "FreeBSD").forEach { osName ->
                    shouldThrow<IOException> { ytDlpPolicy(osName) }
                }
            }
        }
    })
