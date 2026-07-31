package uesugi.core.state.summary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uesugi.common.data.HistoryRecord
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.message.history.truncateContent
import uesugi.core.manage.ManageListQuery
import uesugi.core.state.dispatch.StateWorkResult

/**
 * 摘要服务 - 负责对话摘要生成的业务逻辑
 */
class SummaryService(
    private val summaryRepository: SummaryRepository,
    private val summaryAgent: SummaryAgent
) {

    companion object {
        private val log = logger()
    }

    /**
     * 处理群组对话摘要
     */
    suspend fun processSummaryForGroup(
        botId: String,
        groupId: String,
        batchLimit: Int = ConfigHolder.getStateTuning().summary.batchLimit,
        minimumMessages: Int = ConfigHolder.getStateTuning().summary.minMessages,
        force: Boolean = false
    ): StateWorkResult {
        try {
            log.debug("开始处理群组对话摘要, groupId=$groupId")

            // 1. 获取需要处理的历史消息
            val summaryState = withContext(Dispatchers.IO) {
                summaryRepository.getSummaryState(botId, groupId)
            }
            val initialBatch = summaryState == null
            val lastId = summaryState?.lastProcessedHistoryId ?: 0

            val histories = withContext(Dispatchers.IO) {
                if (initialBatch) {
                    summaryRepository.getLatestHistories(botId, groupId, batchLimit)
                } else {
                    summaryRepository.getHistoriesToProcess(botId, groupId, lastId, batchLimit)
                }
            }

            if (histories.isEmpty()) {
                log.debug("群组 $groupId 没有新消息需要处理摘要")
                return StateWorkResult(0, lastId, hasMore = false)
            }

            if (!force && histories.size < minimumMessages) {
                log.debug("群组 $groupId 消息数量不足 $minimumMessages 条，等待更多消息")
                return StateWorkResult(0, lastId, hasMore = false)
            }

            // 2. 过滤空内容消息
            val maxMessageLength = ConfigHolder.getAgentMaxMessageLength()
            val messages = histories
                .filter { !it.content.isNullOrBlank() }
                .map { it.truncateContent(maxMessageLength) }

            if (messages.isEmpty()) {
                log.debug("群组 $groupId 过滤后无有效消息,跳过摘要生成")
                val maxHistoryId = histories.maxOf { it.id!! }
                withContext(Dispatchers.IO) {
                    summaryRepository.updateSummaryState(botId, groupId, maxHistoryId)
                }
                return StateWorkResult(
                    histories.size,
                    maxHistoryId,
                    hasMore = !initialBatch && histories.size == batchLimit
                )
            }

            // 3. 生成摘要
            doGenerateSummary(botId, groupId, messages)

            val maxHistoryId = histories.maxOf { it.id!! }
            withContext(Dispatchers.IO) {
                summaryRepository.updateSummaryState(botId, groupId, maxHistoryId)
            }

            log.debug("群组 $groupId 对话摘要生成完成")
            return StateWorkResult(
                histories.size,
                maxHistoryId,
                hasMore = !initialBatch && histories.size == batchLimit
            )

        } catch (e: Exception) {
            log.error("处理群组 $groupId 对话摘要失败", e)
            throw e
        }
    }

    /**
     * 生成对话摘要
     */
    private suspend fun doGenerateSummary(
        botId: String,
        groupId: String,
        messages: List<HistoryRecord>
    ) {
        log.debug("开始生成对话摘要, scopeId=$groupId")

        val previousSummaryContext = withContext(Dispatchers.IO) {
            summaryRepository.getLatestSummary(botId, groupId)
        }?.let(::buildPreviousSummaryContext)

        val summary = summaryAgent.generateSummary(messages, groupId, previousSummaryContext)

        withContext(Dispatchers.IO) {
            summaryRepository.saveSummary(
                botId = botId,
                groupId = groupId,
                timeRange = summary.timeRange,
                content = summary.content,
                keyPoints = summary.keyPoints.joinToString("\n"),
                emotionalTone = summary.emotionalTone,
                participantCount = summary.participantIds.distinct().size,
                messageCount = summary.messageCount
            )
        }
        log.info("Conversation summary analysis completed, botId=$botId, groupId=$groupId")
    }

    /**
     * 将上一条摘要拼装为可读的上下文片段，用于喂给摘要生成的 prompt
     */
    private fun buildPreviousSummaryContext(previous: SummaryRecord): String = buildString {
        appendLine("时间范围: ${previous.timeRange}")
        previous.emotionalTone?.takeIf { it.isNotBlank() }?.let {
            appendLine("情感基调: $it")
        }
        appendLine("摘要内容: ${previous.content}")
        if (previous.keyPoints.isNotBlank()) {
            appendLine("关键要点:")
            append(previous.keyPoints)
        }
    }.trimEnd()

    fun getAllSummariesByGroup(
        botId: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<SummaryRecord>, Int> {
        return summaryRepository.getSummariesByGroup(botId, groupId, offset, limit)
    }

    fun getAllSummariesByGroup(
        botId: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<SummaryRecord>, Int> {
        return summaryRepository.getSummariesByGroup(botId, groupId, listQuery)
    }

    fun getSummaryById(id: Int): SummaryRecord? {
        return summaryRepository.getSummaryById(id)
    }

    fun updateSummary(
        id: Int,
        timeRange: String,
        content: String,
        keyPoints: String,
        emotionalTone: String?
    ): SummaryRecord? {
        return summaryRepository.updateSummary(
            id, timeRange, content, keyPoints, emotionalTone
        )
    }

    fun deleteSummary(id: Int): Boolean {
        return summaryRepository.deleteSummary(id)
    }
}
