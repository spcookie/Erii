package uesugi.core.state.summary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jobrunr.scheduling.JobScheduler
import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.state.dispatch.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * 摘要任务 - 事件驱动对话摘要生成与定时清理
 *
 * 负责生成群组对话摘要（历史压缩）。
 */
class SummaryJob(
    val jobScheduler: JobScheduler,
    private val summaryService: SummaryService,
    private val summaryRepository: SummaryRepository
) : StateWorkProcessor {

    override val kind = StateWorkKind.SUMMARY

    companion object {
        private val log = logger()
    }

    private val cleanupMutex = Mutex()

    /**
     * 开启每日摘要清理任务。
     */
    fun openTimingTriggerSignal() {
        jobScheduler.scheduleRecurrently(
            "summary-cleanup-job",
            "0 3 * * *",  // 每日 03:00
            ::doSummaryCleanup
        )
        log.info("Summary event processor initialized, cleanup: daily 03:00")
    }

    override fun accepts(record: HistoryRecord): Boolean = record.messageType == MessageType.TEXT

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            summaryRepository.findGroupsNeedProcessing(botId).forEach { groupId ->
                add(StateWorkKey(botId, groupId, kind))
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult = UsageContext.withUsage(key.botId, key.groupId) {
        summaryService.processSummaryForGroup(
            botId = key.botId,
            groupId = key.groupId,
            batchLimit = policy.batchLimit,
            minimumMessages = policy.minMessages,
            force = force
        )
    }

    /**
     * 执行摘要清理
     * 删除 createdAt 早于 SUMMARY_RETENTION_DAYS 天的记录
     */
    @OptIn(ExperimentalTime::class)
    fun doSummaryCleanup() {
        runBlocking {
            if (cleanupMutex.tryLock()) {
                try {
                    val cutoff = Clock.System.now()
                        .minus(ConfigHolder.getStateTuning().summary.retentionDays.days)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                    log.debug("摘要清理任务开始执行, cutoff={}", cutoff)

                    val deleted = withContext(Dispatchers.IO) {
                        summaryRepository.deleteSummariesBefore(cutoff)
                    }

                    if (deleted > 0) {
                        log.info("Summary cleanup task completed, $deleted expired records deleted (cutoff=$cutoff)")
                    } else {
                        log.debug("摘要清理任务完成, 无过期记录")
                    }
                } catch (e: Exception) {
                    log.error("Summary cleanup task execution failed", e)
                } finally {
                    cleanupMutex.unlock()
                }
            } else {
                log.debug("摘要清理任务正在执行中, 跳过本次调度")
            }
        }
    }
}
