package uesugi.core.state.dispatch

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import uesugi.common.EventBus
import uesugi.common.data.HistoryRecord
import uesugi.common.toolkit.logger
import uesugi.core.message.history.HistorySavedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

data class StateWorkKey(
    val botId: String,
    val groupId: String,
    val kind: StateWorkKind
)

data class StateWorkResult(
    val processedCount: Int,
    val cursor: Int?,
    val hasMore: Boolean,
    val wakeKinds: Set<StateWorkKind> = emptySet()
)

interface StateWorkProcessor {
    val kind: StateWorkKind
    fun accepts(record: HistoryRecord): Boolean
    fun pendingKeys(): Set<StateWorkKey>
    suspend fun process(key: StateWorkKey, policy: StateWorkPolicy, force: Boolean): StateWorkResult
}

class StateWorkCoordinator(
    processors: List<StateWorkProcessor>,
    private val policies: Map<StateWorkKind, StateWorkPolicy>,
    maxConcurrency: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val retryDelays: List<Duration> = listOf(30.seconds, 2.minutes, 10.minutes)
) : AutoCloseable {

    private data class Pending(
        var lastSignalAtNanos: Long,
        var signalCount: Int,
        var forceProcessor: Boolean,
        var scheduled: Job? = null
    )

    private val log = logger()
    private val processorsByKind = processors.associateBy { it.kind }
    private val pending = ConcurrentHashMap<StateWorkKey, Pending>()
    private val keyLocks = ConcurrentHashMap<StateWorkKey, Mutex>()
    private val retries = ConcurrentHashMap<StateWorkKey, Int>()
    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val pendingLock = Any()
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        EventBus.subscribeAsync<HistorySavedEvent>(scope) { event ->
            signal(event.historyRecord)
        }
    }

    fun signal(record: HistoryRecord) {
        processorsByKind.values
            .asSequence()
            .filter { it.accepts(record) }
            .forEach { processor ->
                signal(StateWorkKey(record.botMark, record.groupId, processor.kind))
            }
    }

    fun signal(key: StateWorkKey) {
        schedule(
            key = key,
            increment = 1,
            bypassSignalThreshold = false,
            forceProcessor = false
        )
    }

    fun reconcile() {
        processorsByKind.values.forEach { processor ->
            try {
                processor.pendingKeys().forEach { key ->
                    schedule(
                        key = key,
                        increment = 0,
                        bypassSignalThreshold = true,
                        forceProcessor = false
                    )
                }
            } catch (error: Exception) {
                log.error("State reconciliation failed, kind=${processor.kind}", error)
            }
        }
    }

    private fun schedule(
        key: StateWorkKey,
        increment: Int,
        bypassSignalThreshold: Boolean,
        forceProcessor: Boolean
    ) {
        val policy = policies[key.kind] ?: return
        if (key.kind !in processorsByKind) return
        val now = System.nanoTime()
        synchronized(pendingLock) {
            val state = pending.getOrPut(key) {
                Pending(now, 0, false)
            }
            state.lastSignalAtNanos = now
            state.signalCount += increment
            state.forceProcessor = state.forceProcessor || forceProcessor

            val delayDuration = when {
                bypassSignalThreshold -> Duration.ZERO
                state.signalCount >= policy.minMessages -> {
                    val debounceTarget = state.lastSignalAtNanos + policy.debounce.inWholeNanoseconds
                    (debounceTarget - now).coerceAtLeast(0).nanoseconds
                }

                else -> return
            }
            state.scheduled?.cancel()
            state.scheduled = scope.launch {
                delay(delayDuration)
                runKey(key)
            }
        }
    }

    private suspend fun runKey(key: StateWorkKey) {
        val state = synchronized(pendingLock) {
            pending.remove(key)?.also { it.scheduled = null }
        } ?: return
        val processor = processorsByKind[key.kind] ?: return
        val policy = policies[key.kind] ?: return
        val forceProcessor = state.forceProcessor
        val lock = keyLocks.computeIfAbsent(key) { Mutex() }

        lock.withLock {
            semaphore.withPermit {
                try {
                    val result = processor.process(key, policy, forceProcessor)
                    retries.remove(key)
                    if (result.processedCount > 0) {
                        log.info(
                            "State work completed, kind={}, botId={}, groupId={}, processed={}, cursor={}, hasMore={}",
                            key.kind, key.botId, key.groupId, result.processedCount, result.cursor, result.hasMore
                        )
                    } else {
                        log.debug(
                            "State work checked, kind={}, botId={}, groupId={}, processed=0, cursor={}, hasMore={}",
                            key.kind, key.botId, key.groupId, result.cursor, result.hasMore
                        )
                    }
                    if (result.hasMore && policy.backlogMode == BacklogMode.SEQUENTIAL) {
                        schedule(
                            key = key,
                            increment = 0,
                            bypassSignalThreshold = true,
                            forceProcessor = true
                        )
                    }
                    result.wakeKinds.forEach { kind ->
                        schedule(
                            key = key.copy(kind = kind),
                            increment = 0,
                            bypassSignalThreshold = true,
                            forceProcessor = false
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.error(
                        "State work failed, kind=${key.kind}, botId=${key.botId}, groupId=${key.groupId}",
                        error
                    )
                    scheduleRetry(key)
                }
            }
        }
    }

    private fun scheduleRetry(key: StateWorkKey) {
        val attempt = retries.merge(key, 1, Int::plus) ?: 1
        val retryDelay = retryDelays.getOrElse(attempt - 1) { retryDelays.last() }
        scope.launch {
            delay(retryDelay)
            if (retries[key] == attempt) {
                schedule(
                    key = key,
                    increment = 0,
                    bypassSignalThreshold = true,
                    forceProcessor = true
                )
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
