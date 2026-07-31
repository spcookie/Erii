package uesugi.core.state.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.jobrunr.scheduling.JobScheduler
import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.state.dispatch.*

internal suspend fun runConfiguredMemoryRebuilds(
    options: MemoryRebuildOptions,
    rebuildEntities: suspend () -> Unit = {},
    rebuildVector: suspend () -> Unit,
    rebuildGraph: suspend () -> Unit,
    logFailure: (String, Exception) -> Unit
) {
    if (options.entities) {
        try {
            rebuildEntities()
        } catch (e: Exception) {
            logFailure("entities", e)
        }
    }
    if (options.vector || options.entities) {
        try {
            rebuildVector()
        } catch (e: Exception) {
            logFailure("vector", e)
        }
    }
    if (options.graph || options.entities) {
        try {
            rebuildGraph()
        } catch (e: Exception) {
            logFailure("graph", e)
        }
    }
}

/**
 * 记忆任务 - 事件驱动记忆处理与定时清理
 *
 * 仅负责调度和组合逻辑，业务逻辑封装在 MemoryService 中
 */
class MemoryJob(
    val jobScheduler: JobScheduler,
    private val memoryRepository: MemoryRepository,
    private val memoryService: MemoryService
) : StateWorkProcessor {

    override val kind = StateWorkKind.MEMORY

    companion object {
        private val log = logger()
    }

    private val mutex = Mutex()

    /**
     * 开启每日记忆清理任务。
     */
    fun openTimingTriggerSignal(rebuildOptions: MemoryRebuildOptions = MemoryRebuildOptions.from()) {
        runConfiguredRebuilds(rebuildOptions)
        jobScheduler.scheduleRecurrently(
            "memory-expired-cleanup-job",
            "0 2 * * *",
            ::doExpiredMemoryCleanup
        )
        jobScheduler.scheduleRecurrently(
            "memory-stale-recall-cleanup-job",
            "30 2 * * *",
            ::doStaleRecalledMemoryCleanup
        )
        log.info("Memory event processor and cleanup timers initialized")
    }

    private fun runConfiguredRebuilds(options: MemoryRebuildOptions) {
        if (!options.entities && !options.vector && !options.graph) return
        runBlocking {
            if (mutex.tryLock()) {
                try {
                    runConfiguredMemoryRebuilds(
                        options = options,
                        rebuildEntities = {
                            log.info("Configured fact entity rebuild started")
                            withContext(Dispatchers.IO) {
                                memoryService.rebuildFactEntities()
                            }
                        },
                        rebuildVector = {
                            log.info("Configured fact vector rebuild started")
                            withContext(Dispatchers.IO) {
                                memoryService.rebuildFactVectors()
                            }
                        },
                        rebuildGraph = {
                            log.info("Configured fact graph rebuild started")
                            withContext(Dispatchers.IO) {
                                memoryService.rebuildFactGraphs()
                            }
                        },
                        logFailure = { name, error ->
                            log.error("Configured fact $name rebuild failed", error)
                        }
                    )
                } finally {
                    mutex.unlock()
                }
            } else {
                log.debug("Memory task is running, skip configured memory rebuild")
            }
        }
    }

    override fun accepts(record: HistoryRecord): Boolean = record.messageType == MessageType.TEXT

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            memoryRepository.findGroupsNeedProcessing(botId).forEach { groupId ->
                add(StateWorkKey(botId, groupId, kind))
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult = UsageContext.withUsage(key.botId, key.groupId) {
        memoryService.processGroupMemory(
            botId = key.botId,
            groupId = key.groupId,
            batchLimit = policy.batchLimit,
            minimumMessages = policy.minMessages,
            force = force
        )
    }

    fun doExpiredMemoryCleanup() {
        runBlocking {
            if (mutex.tryLock()) {
                try {
                    log.debug("Expired memory cleanup started")
                    withContext(Dispatchers.IO) {
                        memoryService.deleteExpiredFacts()
                    }
                    log.debug("Expired memory cleanup completed")
                } catch (e: Exception) {
                    log.error("Expired memory cleanup failed", e)
                } finally {
                    mutex.unlock()
                }
            } else {
                log.debug("Memory task is running, skip expired memory cleanup")
            }
        }
    }

    fun doStaleRecalledMemoryCleanup() {
        runBlocking {
            if (mutex.tryLock()) {
                try {
                    val staleRecallDays = ConfigHolder.getStateTuning().memory.staleRecallDays
                    log.debug("Stale unrecalled memory cleanup started, days=$staleRecallDays")
                    withContext(Dispatchers.IO) {
                        memoryService.deleteStaleUnrecalledFacts(staleRecallDays)
                    }
                    log.debug("Stale unrecalled memory cleanup completed")
                } catch (e: Exception) {
                    log.error("Stale unrecalled memory cleanup failed", e)
                } finally {
                    mutex.unlock()
                }
            } else {
                log.debug("Memory task is running, skip stale unrecalled memory cleanup")
            }
        }
    }
}
