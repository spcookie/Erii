package uesugi.core.state.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.component.usage.UsageContext
import uesugi.core.message.history.orEmptyTruncatedHistoryContent
import uesugi.core.state.dispatch.*
import kotlin.time.ExperimentalTime

class FlowJob(
    private val flowAgent: FlowAgent,
    private val flowRepository: FlowRepository
) : StateWorkProcessor {

    override val kind = StateWorkKind.FLOW

    companion object {
        private val log = logger()
    }

    fun openTimingTriggerSignal() {
        for (bot in BotManage.getAllBotIds()) {
            val configKey = BotManage.getConfigKey(bot)
            for (group in ConfigHolder.getEffectiveEnableGroups(configKey)) {
                log.info("init flow for bot $bot in group $group")
                ensureFlowGaugeExists(bot, group)
            }
        }
        log.info("Flow event processor initialized")
    }

    override fun accepts(record: HistoryRecord): Boolean = record.messageType == MessageType.TEXT

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            flowRepository.findGroupsNeedProcessing(botId).forEach { groupId ->
                add(StateWorkKey(botId, groupId, kind))
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult {
        ensureFlowGaugeExists(key.botId, key.groupId)
        return UsageContext.withUsage(key.botId, key.groupId) {
            processGroupFlow(key.botId, key.groupId, policy, force)
        }
    }

    private fun ensureFlowGaugeExists(botId: String, groupId: String) {
        val flowGaugeManager by GlobalContext.get().inject<FlowGaugeManager>()
        val configKey = BotManage.getConfigKey(botId)
        val baseDesire = ConfigHolder.getOnebotBots()[configKey]?.groups?.get(groupId)?.desire
            ?: ConfigHolder.getStateTuning().volition.baseDesireDefault
        flowGaugeManager.getOrCreate(botId, groupId, BotManage.getBot(botId).role.emoticon, baseDesire)
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun processGroupFlow(
        botId: String,
        groupId: String,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult {
        log.debug("开始处理群组心流, groupId=$groupId")

        try {
            val flowState = withContext(Dispatchers.IO) {
                flowRepository.getFlowState(botId, groupId)
            }
            val lastId = flowState?.lastProcessedHistoryId ?: 0

            val histories = withContext(Dispatchers.IO) {
                flowRepository.getLatestHistoriesToProcess(botId, groupId, lastId, policy.batchLimit)
            }

            if (histories.isEmpty() || (!force && histories.size < policy.minMessages)) {
                return StateWorkResult(0, lastId, hasMore = false)
            }

            log.debug("群组 $groupId 获取到 ${histories.size} 条新消息")

            val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            val messages = histories.map {
                FlowMessage(
                    id = it.id.value,
                    groupId = it.groupId,
                    userId = it.userId,
                    time = it.createdAt,
                    content = it.content.orEmptyTruncatedHistoryContent(maxMessageLength)
                )
            }

            if (messages.isEmpty()) {
                log.debug("群组 $groupId 消息转换后为空, 跳过处理")
                val maxHistoryId = histories.maxOf { it.id.value }
                withContext(Dispatchers.IO) {
                    flowRepository.updateFlowState(botId, groupId, maxHistoryId)
                }
                return StateWorkResult(histories.size, maxHistoryId, hasMore = false)
            }

            if (flowState == null) {
                withContext(Dispatchers.IO) {
                    flowRepository.updateFlowState(botId, groupId, lastId)
                }
            }

            val success = flowAgent.analysis(messages, botId, groupId)
            if (!success) {
                throw IllegalStateException("Flow analysis failed, botId=$botId, groupId=$groupId")
            }

            val maxHistoryId = histories.maxOf { it.id.value }
            withContext(Dispatchers.IO) {
                flowRepository.updateFlowState(botId, groupId, maxHistoryId)
            }

            log.debug("群组 $groupId 心流处理完成, 最大 historyId=$maxHistoryId")
            return StateWorkResult(histories.size, maxHistoryId, hasMore = false)

        } catch (e: Exception) {
            log.error("处理群组 $groupId 心流失败", e)
            throw e
        }
    }

}
