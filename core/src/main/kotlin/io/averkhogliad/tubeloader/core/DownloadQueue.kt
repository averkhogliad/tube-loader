package io.averkhogliad.tubeloader.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * FIFO queue of submitted work with one global limit on the tasks running at once.
 *
 * Owns execution only: it takes a launch lambda, starts it as a coroutine, hands out a slot by the
 * current limit and frees the slot when the task ends. Statuses, task ids and paths are none of its
 * business. Every mutation of its own structures happens inside a coroutine on the injected
 * dispatcher, which is confined to a single worker, so plain collections are safe without locks.
 * The work itself runs in [workContext] instead: a task that reads a blocking stream would
 * otherwise hold the single confined worker and serialize downloads that are meant to run in
 * parallel.
 *
 * Creates no resources but its own scope, and that one is a child of the scope the assembly passes
 * in: the queue starts it, feeds it and cancels only it. The limit comes from the injected [config]
 * stream, so the owner of the configuration decides when the limit changes; the queue only reacts
 * to the new value. Stopping the queue is its own operation: work still in flight is cancelled and
 * awaited within a budget, and new work is refused afterwards.
 */
class DownloadQueue(
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val workContext: CoroutineContext,
    private val config: StateFlow<AppConfig>,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + dispatcher)
    private val waiting = ArrayDeque<Waiter>()
    private val running = ArrayDeque<Job>()

    @Volatile
    private var stopped = false

    init {
        // a work context with its own job wins the merge in withContext: the work would stop being a
        // child of the queue, and shutdown would neither cancel it nor wait for it
        require(workContext[Job] == null) {
            "workContext must not carry a Job: the work would escape the queue scope"
        }
        scope.launch { config.collect { pump() } }
    }

    fun submit(work: suspend () -> Unit): Job {
        // the parent scope can die on its own, and then a launched job is born cancelled: the work
        // would never run and the task would sit in whatever state the caller published
        check(!stopped && scope.isActive) { "The download queue is closed" }
        return scope.launch {
            val job = coroutineContext[Job]!!
            val waiter = Waiter(job, CompletableDeferred())
            waiting += waiter
            pump()
            try {
                waiter.gate.await()
                withContext(workContext) { work() }
            } finally {
                running.remove(job)
                waiting.remove(waiter)
                pump()
            }
        }
    }

    /**
     * Stops the queue and waits for everything it started to finish, but no longer than [timeout].
     * The result says whether the wait fit the budget: work blocked on an external process ignores
     * the cancellation and outlives it.
     */
    suspend fun shutdown(timeout: Duration): Boolean {
        stopped = true
        val stopping = job
        // the collector holds the queue job alive after the work is gone, so an idle queue is not
        // an already completed job: ask the confined worker what is actually in flight
        val idle = withContext(dispatcher) { waiting.isEmpty() && running.isEmpty() }
        stopping.cancel()
        if (idle) return true
        return withTimeoutOrNull(timeout) { stopping.join() } != null
    }

    /** Re-reads the limit, so work that waited for a slot starts when the limit grew. */
    private fun pump() {
        running.removeAll { !it.isActive }
        while (running.size < config.value.maxParallelDownloads && waiting.isNotEmpty()) {
            val waiter = waiting.removeFirst()
            if (!waiter.job.isActive) continue
            running += waiter.job
            waiter.gate.complete(Unit)
        }
    }
}

private class Waiter(val job: Job, val gate: CompletableDeferred<Unit>)
