package uesugi.core.state.evolution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jobrunr.scheduling.BackgroundJob
import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.message.history.truncateHistoryContent
import uesugi.core.state.dispatch.*
import kotlin.time.Duration.Companion.hours

class EvolutionJob(
    private val evolutionService: EvolutionService,
    private val extractionAgent: ExtractionAgent,
    private val evolutionRepository: EvolutionRepository
) : StateWorkProcessor {
    override val kind = StateWorkKind.EVOLUTION
    companion object {
        private val log = logger()
    }

    /**
     * 开启定时触发
     *
     * 每 1 小时执行一次模因进化处理
     */
    fun openTimingTriggerSignal() {
        BackgroundJob.scheduleRecurrently(
            "evolution-decay-job",
            "0 */1 * * *",  // 每 1 小时一次
            ::doEvolutionDecay
        )
        log.info("Evolution event processor initialized, decay cycle: per hour")
    }

    override fun accepts(record: HistoryRecord): Boolean =
        record.messageType == MessageType.TEXT && record.userId != record.botId

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            evolutionRepository.findGroupsNeedProcessing(botId).forEach { groupId ->
                add(StateWorkKey(botId, groupId, kind))
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult = UsageContext.withUsage(key.botId, key.groupId) {
        val state = withContext(Dispatchers.IO) {
            evolutionRepository.getState(key.botId, key.groupId)
        }
        if (state == null) {
            val latestId = withContext(Dispatchers.IO) {
                evolutionRepository.latestHistoryId(key.botId, key.groupId)
            } ?: return@withUsage StateWorkResult(0, null, false)
            val recentMessages = withContext(Dispatchers.IO) {
                evolutionService.getMostActiveMessages(
                    key.botId, key.groupId, policy.batchLimit,
                    ConfigHolder.getStateTuning().evolution.recentRangeHours.hours
                )
            }
            if (recentMessages.isNotEmpty()) {
                val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
                processMessages(
                    key.botId,
                    key.groupId,
                    recentMessages.map { it.truncateHistoryContent(maxMessageLength) },
                    recentMessages
                )
            }
            withContext(Dispatchers.IO) {
                evolutionRepository.updateState(key.botId, key.groupId, latestId)
            }
            return@withUsage StateWorkResult(recentMessages.size, latestId, false)
        }

        val histories = withContext(Dispatchers.IO) {
            evolutionRepository.getMessagesAfter(
                key.botId, key.groupId, state.lastProcessedHistoryId, policy.batchLimit
            )
        }
        if (histories.isEmpty() || (!force && histories.size < policy.minMessages)) {
            return@withUsage StateWorkResult(0, state.lastProcessedHistoryId, false)
        }
        val recentMessages = evolutionService.filterMessages(histories.map { it.content })
        if (recentMessages.isNotEmpty()) {
            val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            val analysisMessages = recentMessages.map { it.truncateHistoryContent(maxMessageLength) }
            processMessages(key.botId, key.groupId, analysisMessages, recentMessages)
        }
        val cursor = histories.last().id
        withContext(Dispatchers.IO) {
            evolutionRepository.updateState(key.botId, key.groupId, cursor)
        }
        StateWorkResult(histories.size, cursor, histories.size == policy.batchLimit)
    }

    fun doEvolutionDecay() {
        runBlocking {
            for (botId in BotManage.getAllBotIds()) {
                val groups = withContext(Dispatchers.IO) {
                    evolutionRepository.getActiveGroups(botId)
                }
                groups.forEach { groupId ->
                    val recent = withContext(Dispatchers.IO) {
                        evolutionService.getMostActiveMessages(
                            botId, groupId,
                            ConfigHolder.getStateTuning().evolution.recentMessageLimit,
                            ConfigHolder.getStateTuning().evolution.recentRangeHours.hours
                        )
                    }
                    withContext(Dispatchers.IO) {
                        evolutionService.decayOldWords(botId, groupId, recent)
                    }
                }
            }
        }
    }

    private suspend fun processMessages(
        botId: String,
        groupId: String,
        analysisMessages: List<String>,
        recentMessages: List<String>
    ) {
        val slangWords = extractionAgent.extractSlangWords(analysisMessages)

        if (slangWords.isEmpty()) {
            log.warn("Evolutions not extracted")
        } else {
            log.info("Evolutions extracted, size=${slangWords.size}")
            slangWords.forEachIndexed { index, slang ->
                log.debug("  ${index + 1}. ${slang.word} (${slang.type}) - ${slang.meaning}")
            }
        }

        withContext(Dispatchers.IO) {
            for (slangWord in slangWords) {
                evolutionService.addOrUpdateWord(botId, groupId, slangWord)
            }
            evolutionService.decayOldWords(botId, groupId, recentMessages)
        }
    }
}
