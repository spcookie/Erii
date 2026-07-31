package uesugi.core.state.volition

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
import kotlin.time.ExperimentalTime

/**
 * 意愿仓库 - 负责数据库操作
 */
class VolitionRepository {

    companion object {
        private val log = logger()
    }

    /**
     * 查找需要处理的群组
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
                val volitionState = VolitionStateEntity.find(
                    (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
                ).firstOrNull()

                val lastProcessedId = volitionState?.lastProcessedHistoryId ?: 0

                val newMessageCount = HistoryEntity.count(
                    (HistoryTable.botId eq botId) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.id greater lastProcessedId)
                )

                newMessageCount > 0
            }
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
     * 获取意愿状态
     */
    fun getVolitionState(botId: String, groupId: String): VolitionStateEntity? {
        return transaction {
            VolitionStateEntity.find(
                (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
            ).firstOrNull()
        }
    }

    /**
     * 更新意愿状态
     */
    @OptIn(ExperimentalTime::class)
    fun updateVolitionState(botId: String, groupId: String, lastHistoryId: Int) {
        transaction {
            val now = kotlin.time.Clock.System.now()
            val tz = TimeZone.currentSystemDefault()
            val instant = now.toLocalDateTime(tz)

            val existing = VolitionStateEntity.find(
                (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.lastProcessedHistoryId = lastHistoryId
                existing.lastProcessedAt = instant
            } else {
                VolitionStateEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    this.lastProcessedHistoryId = lastHistoryId
                    this.lastProcessedAt = instant
                }
            }
            log.debug("主动意愿状态已更新, groupId=$groupId, lastHistoryId=$lastHistoryId")
        }
    }

    /**
     * 直接更新意愿状态（用于外部 API 修改）
     */
    fun updateVolitionStateDirect(botId: String, groupId: String, fatigue: Double, stimulus: Double) {
        transaction {
            val existing = VolitionStateEntity.find(
                (VolitionStateTable.botId eq botId) and (VolitionStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.fatigue = fatigue
                existing.stimulus = stimulus
            } else {
                VolitionStateEntity.new {
                    this.botId = botId
                    this.groupId = groupId
                    this.fatigue = fatigue
                    this.stimulus = stimulus
                }
            }
            log.debug("直接更新意愿状态, groupId=$groupId, fatigue=$fatigue, stimulus=$stimulus")
        }
    }
}
