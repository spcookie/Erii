package uesugi.core.state.flow

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.toolkit.logger
import kotlin.time.Clock

/**
 * 心流仓库 - 负责数据库操作
 */
class FlowRepository {

    companion object {
        private val log = logger()
    }

    /**
     * 查找需要处理的群组（至少需要3条新消息）
     */
    fun findGroupsNeedProcessing(botId: String): List<String> {
        return transaction {
            val allGroupIds = HistoryTable
                .select(HistoryTable.groupId)
                .where { HistoryTable.botId eq botId }
                .groupBy(HistoryTable.groupId)
                .map { it[HistoryTable.groupId] }
                .distinct()

            allGroupIds.filter { groupId ->
                val flowState = FlowStateEntity.find(
                    (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
                ).firstOrNull()

                val lastProcessedId = flowState?.lastProcessedHistoryId ?: 0

                val newMessageCount = HistoryEntity.count(
                    (HistoryTable.botId eq botId) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.id greater lastProcessedId)
                )

                newMessageCount > 0
            }
        }
    }

    /**
     * 获取待处理的历史消息
     */
    fun getHistoriesToProcess(
        botId: String,
        groupId: String,
        lastHistoryId: Int,
        limit: Int = 100
    ): List<HistoryEntity> {
        return transaction {
            HistoryEntity.find(
                (HistoryTable.botId eq botId) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.id greater lastHistoryId)
            )
                .orderBy(HistoryTable.id to SortOrder.ASC)
                .limit(limit)
                .toList()
        }
    }

    fun getLatestHistoriesToProcess(
        botId: String,
        groupId: String,
        lastHistoryId: Int,
        limit: Int
    ): List<HistoryEntity> = transaction {
        HistoryEntity.find(
            (HistoryTable.botId eq botId) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.id greater lastHistoryId)
        )
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
            .reversed()
    }

    /**
     * 获取心流状态
     */
    fun getFlowState(botId: String, groupId: String): FlowStateEntity? {
        return transaction {
            FlowStateEntity.find(
                (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
            ).firstOrNull()
        }
    }

    /**
     * 更新心流状态
     */
    fun updateFlowState(botId: String, groupId: String, lastHistoryId: Int) {
        transaction {
            val now = Clock.System.now()
            val tz = TimeZone.currentSystemDefault()
            val instant = now.toLocalDateTime(tz)

            val existing = FlowStateEntity.find(
                (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.lastProcessedHistoryId = lastHistoryId
                existing.lastProcessedAt = instant
            } else {
                FlowStateEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    this.lastProcessedHistoryId = lastHistoryId
                    this.lastProcessedAt = instant
                }
            }
            log.debug("心流状态已更新, groupId=$groupId, lastHistoryId=$lastHistoryId")
        }
    }

    /**
     * 直接更新心流状态（用于外部 API 修改）
     */
    fun updateFlowStateDirect(botId: String, groupId: String, flowValue: Double, currentTopic: String) {
        transaction {
            val existing = FlowStateEntity.find(
                (FlowStateTable.botId eq botId) and (FlowStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.flowValue = flowValue
                existing.currentTopic = currentTopic
            } else {
                FlowStateEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    this.flowValue = flowValue
                    this.currentTopic = currentTopic
                }
            }
            log.debug("直接更新心流状态, groupId=$groupId, flowValue=$flowValue")
        }
    }
}
