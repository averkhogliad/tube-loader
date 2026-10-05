package io.averkhogliad.tubeloader.core.contract

import io.kotest.core.spec.style.FreeSpec

/**
 * Runs the contract suite against the reference adapter: proof that the suite itself is runnable and
 * green, and that an adapter plugs in with one connection.
 */
class ReferenceAdapterContractTest :
    FreeSpec({
        val fixtures = referenceAdapterFixtures()
        include(sourceAdapterContract("reference", fixtures) { ReferenceSourceAdapter(fixtures) })
    })
