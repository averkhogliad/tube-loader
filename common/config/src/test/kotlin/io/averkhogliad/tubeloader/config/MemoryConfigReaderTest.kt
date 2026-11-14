package io.averkhogliad.tubeloader.config

import io.kotest.core.spec.style.FreeSpec

class MemoryConfigReaderTest :
    FreeSpec({

        include(configReaderContract { values -> MemoryConfigReader(values) })
    })
