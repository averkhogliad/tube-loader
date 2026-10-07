package io.averkhogliad.tubeloader.adapters.rutube

import io.averkhogliad.tubeloader.core.config.HttpToolConfig
import io.averkhogliad.tubeloader.core.contract.sourceAdapterContract
import io.averkhogliad.tubeloader.core.port.FakeMediaTool
import io.kotest.core.spec.style.FreeSpec
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the shared adapter contract against the Rutube adapter, driven by the golden recording.
 */
class RutubeAdapterContractTest :
    FreeSpec({
        val fixtures = rutubeAdapterFixtures()
        include(
            sourceAdapterContract("rutube", fixtures) {
                RutubeSourceAdapter(
                    rutubeRecordedHttp(),
                    FakeMediaTool().copyStreams(),
                    { HttpToolConfig(perAttemptTimeout = 30.seconds) },
                )
            },
        )
    })
