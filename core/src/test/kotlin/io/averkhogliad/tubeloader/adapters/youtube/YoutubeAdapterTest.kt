package io.averkhogliad.tubeloader.adapters.youtube

import io.averkhogliad.tubeloader.core.contract.sourceAdapterContract
import io.kotest.core.spec.style.FreeSpec

/**
 * Runs the shared adapter contract against the YouTube adapter, driven by the golden recording of the tool.
 */
class YoutubeAdapterTest :
    FreeSpec({
        val golden = youtubeFixtures()
        include(sourceAdapterContract("youtube", golden.cases, golden.adapter))
    })
