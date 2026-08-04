package uesugi.core.state.meme

import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.component.usage.UsageContext
import uesugi.core.state.dispatch.*

class MemeCollectProcessor(
    private val job: MemeJob,
    private val service: MemeService
) : StateWorkProcessor {
    override val kind = StateWorkKind.MEME_COLLECT

    override fun accepts(record: HistoryRecord): Boolean = record.messageType == MessageType.IMAGE

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            val configKey = BotManage.getConfigKey(botId)
            for (groupId in ConfigHolder.resolveEnabledGroups(configKey, botId)) {
                val cursor = service.getScanState(botId, groupId)?.lastHistoryId ?: 0
                if (service.getRecentImageMessages(botId, groupId, cursor, 1).isNotEmpty()) {
                    add(StateWorkKey(botId, groupId, kind))
                }
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult = UsageContext.withUsage(key.botId, key.groupId) {
        job.processGroupCollection(key.botId, key.groupId, policy.batchLimit)
    }
}

class MemeAnalyzeProcessor(
    private val job: MemeJob,
    private val service: MemeService
) : StateWorkProcessor {
    override val kind = StateWorkKind.MEME_ANALYZE

    override fun accepts(record: HistoryRecord): Boolean = false

    override fun pendingKeys(): Set<StateWorkKey> = buildSet {
        for (botId in BotManage.getAllBotIds()) {
            val configKey = BotManage.getConfigKey(botId)
            for (groupId in ConfigHolder.resolveEnabledGroups(configKey, botId)) {
                if (service.getPendingAnalysisMemes(botId, groupId).isNotEmpty()) {
                    add(StateWorkKey(botId, groupId, kind))
                }
            }
        }
    }

    override suspend fun process(
        key: StateWorkKey,
        policy: StateWorkPolicy,
        force: Boolean
    ): StateWorkResult = UsageContext.withUsage(key.botId, key.groupId) {
        job.processGroupExtraction(key.botId, key.groupId, policy.batchLimit)
    }
}
