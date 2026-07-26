package uesugi.core.state.dispatch

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class StateWorkCoordinatorTest {
    @Test
    fun `signals are coalesced until debounce expires`() = runBlocking {
        val processor = FakeProcessor()
        val coordinator = coordinator(processor, minimum = 2, debounceMs = 30)
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        coordinator.signal(key)
        await { processor.calls.get() == 1 }
        delay(50)

        assertEquals(1, processor.calls.get())
        coordinator.close()
    }

    @Test
    fun `work below message threshold does not run solely because time passes`() = runBlocking {
        val processor = FakeProcessor()
        val coordinator = coordinator(processor, minimum = 5, debounceMs = 10)
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        delay(100)

        assertEquals(0, processor.calls.get())
        coordinator.close()
    }

    @Test
    fun `reconciliation restores work when an event signal was lost`() = runBlocking {
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)
        val processor = FakeProcessor(pending = setOf(key))
        val coordinator = coordinator(processor, minimum = 5, debounceMs = 10)

        coordinator.reconcile()
        await { processor.calls.get() == 1 }

        assertEquals(false, processor.forced[0])
        coordinator.close()
    }

    @Test
    fun `repeated reconciliation checks small backlog without invoking model`() = runBlocking {
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)
        val checks = AtomicInteger()
        val modelCalls = AtomicInteger()
        val processor = object : StateWorkProcessor {
            override val kind = StateWorkKind.MEMORY
            override fun accepts(record: uesugi.common.data.HistoryRecord): Boolean = true
            override fun pendingKeys(): Set<StateWorkKey> = setOf(key)

            override suspend fun process(
                key: StateWorkKey,
                policy: StateWorkPolicy,
                force: Boolean
            ): StateWorkResult {
                checks.incrementAndGet()
                if (force || 2 >= policy.minMessages) {
                    modelCalls.incrementAndGet()
                }
                return StateWorkResult(0, null, false)
            }
        }
        val coordinator = coordinator(processor, minimum = 5, debounceMs = 10)

        repeat(3) { index ->
            coordinator.reconcile()
            await { checks.get() == index + 1 }
        }

        assertEquals(3, checks.get())
        assertEquals(0, modelCalls.get())
        coordinator.close()
    }

    @Test
    fun `one reconciliation failure does not block other processors`() = runBlocking {
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)
        val processor = FakeProcessor(pending = setOf(key))
        val coordinator = StateWorkCoordinator(
            processors = listOf(FailingReconciliationProcessor, processor),
            policies = mapOf(
                StateWorkKind.MEMORY to StateWorkPolicy(
                    BacklogMode.SEQUENTIAL,
                    5.milliseconds,
                    5,
                    10
                )
            ),
            maxConcurrency = 2,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retryDelays = listOf(5.milliseconds)
        )

        coordinator.reconcile()
        await { processor.calls.get() == 1 }

        coordinator.close()
    }

    @Test
    fun `completed work can wake a dependent processor without faking message count`() = runBlocking {
        val analyzerCalls = AtomicInteger()
        val analyzerForced = AtomicInteger()
        val collector = object : StateWorkProcessor {
            override val kind = StateWorkKind.MEME_COLLECT
            override fun accepts(record: uesugi.common.data.HistoryRecord): Boolean = false
            override fun pendingKeys(): Set<StateWorkKey> = emptySet()
            override suspend fun process(
                key: StateWorkKey,
                policy: StateWorkPolicy,
                force: Boolean
            ) = StateWorkResult(1, 10, false, setOf(StateWorkKind.MEME_ANALYZE))
        }
        val analyzer = object : StateWorkProcessor {
            override val kind = StateWorkKind.MEME_ANALYZE
            override fun accepts(record: uesugi.common.data.HistoryRecord): Boolean = false
            override fun pendingKeys(): Set<StateWorkKey> = emptySet()
            override suspend fun process(
                key: StateWorkKey,
                policy: StateWorkPolicy,
                force: Boolean
            ): StateWorkResult {
                analyzerCalls.incrementAndGet()
                if (force) analyzerForced.incrementAndGet()
                return StateWorkResult(1, null, false)
            }
        }
        val coordinator = StateWorkCoordinator(
            processors = listOf(collector, analyzer),
            policies = mapOf(
                StateWorkKind.MEME_COLLECT to StateWorkPolicy(
                    BacklogMode.SEQUENTIAL, 1.milliseconds, 1, 500
                ),
                StateWorkKind.MEME_ANALYZE to StateWorkPolicy(
                    BacklogMode.SEQUENTIAL, 1.milliseconds, 1, 20
                )
            ),
            maxConcurrency = 2,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            retryDelays = listOf(5.milliseconds)
        )

        coordinator.signal(StateWorkKey("bot", "group", StateWorkKind.MEME_COLLECT))
        await { analyzerCalls.get() == 1 }

        assertEquals(0, analyzerForced.get())
        coordinator.close()
    }

    @Test
    fun `sequential work immediately continues while more batches remain`() = runBlocking {
        val processor = FakeProcessor(hasMoreForCalls = 2)
        val coordinator = coordinator(processor, minimum = 1, debounceMs = 5)
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        await { processor.calls.get() == 3 }

        assertEquals(3, processor.calls.get())
        assertEquals(false, processor.forced[0])
        assertEquals(true, processor.forced[1])
        assertEquals(true, processor.forced[2])
        coordinator.close()
    }

    @Test
    fun `same key never processes concurrently`() = runBlocking {
        val processor = FakeProcessor(processDelayMs = 35, hasMoreForCalls = 1)
        val coordinator = coordinator(processor, minimum = 1, debounceMs = 5)
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        delay(15)
        coordinator.signal(key)
        await { processor.calls.get() >= 2 }

        assertEquals(1, processor.maxActive.get())
        coordinator.close()
    }

    @Test
    fun `global concurrency never exceeds configured limit`() = runBlocking {
        val processor = FakeProcessor(processDelayMs = 40)
        val coordinator = coordinator(
            processor = processor,
            minimum = 1,
            debounceMs = 1,
            maxConcurrency = 2
        )

        repeat(6) { index ->
            coordinator.signal(StateWorkKey("bot", "group-$index", StateWorkKind.MEMORY))
        }
        await { processor.calls.get() == 6 }
        delay(200)

        assertEquals(2, processor.maxActive.get())
        coordinator.close()
    }

    @Test
    fun `failed work is retried`() = runBlocking {
        val processor = FakeProcessor(failForCalls = 1)
        val coordinator = coordinator(
            processor = processor,
            minimum = 1,
            debounceMs = 1,
            retryDelayMs = 5
        )
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        await { processor.calls.get() == 2 }

        assertEquals(2, processor.calls.get())
        assertEquals(false, processor.forced[0])
        assertEquals(true, processor.forced[1])
        coordinator.close()
    }

    @Test
    fun `successful new signal invalidates an older retry`() = runBlocking {
        val processor = FakeProcessor(failForCalls = 1)
        val coordinator = coordinator(
            processor = processor,
            minimum = 1,
            debounceMs = 1,
            retryDelayMs = 60
        )
        val key = StateWorkKey("bot", "group", StateWorkKind.MEMORY)

        coordinator.signal(key)
        await { processor.calls.get() == 1 }
        coordinator.signal(key)
        await { processor.calls.get() == 2 }
        delay(90)

        assertEquals(2, processor.calls.get())
        coordinator.close()
    }

    private fun coordinator(
        processor: StateWorkProcessor,
        minimum: Int,
        debounceMs: Long,
        maxConcurrency: Int = 2,
        retryDelayMs: Long = 5
    ) = StateWorkCoordinator(
        processors = listOf(processor),
        policies = mapOf(
            StateWorkKind.MEMORY to StateWorkPolicy(
                BacklogMode.SEQUENTIAL,
                debounceMs.milliseconds,
                minimum,
                10
            )
        ),
        maxConcurrency = maxConcurrency,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        retryDelays = listOf(retryDelayMs.milliseconds)
    )

    private suspend fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
        error("condition was not met")
    }

    private class FakeProcessor(
        private val processDelayMs: Long = 0,
        private val hasMoreForCalls: Int = 0,
        private val failForCalls: Int = 0,
        private val pending: Set<StateWorkKey> = emptySet()
    ) : StateWorkProcessor {
        override val kind = StateWorkKind.MEMORY
        val calls = AtomicInteger()
        val maxActive = AtomicInteger()
        val forced = ConcurrentHashMap<Int, Boolean>()
        private val active = AtomicInteger()

        override fun accepts(record: uesugi.common.data.HistoryRecord): Boolean = true

        override fun pendingKeys(): Set<StateWorkKey> = pending

        override suspend fun process(key: StateWorkKey, policy: StateWorkPolicy, force: Boolean): StateWorkResult {
            val call = calls.incrementAndGet()
            forced[call - 1] = force
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, nowActive) }
            try {
                if (processDelayMs > 0) delay(processDelayMs)
                if (call <= failForCalls) error("expected test failure")
            } finally {
                active.decrementAndGet()
            }
            return StateWorkResult(processedCount = 1, cursor = call, hasMore = call <= hasMoreForCalls)
        }
    }

    private object FailingReconciliationProcessor : StateWorkProcessor {
        override val kind = StateWorkKind.FLOW

        override fun accepts(record: uesugi.common.data.HistoryRecord): Boolean = false

        override fun pendingKeys(): Set<StateWorkKey> = error("expected reconciliation failure")

        override suspend fun process(
            key: StateWorkKey,
            policy: StateWorkPolicy,
            force: Boolean
        ): StateWorkResult = error("not used")
    }
}
