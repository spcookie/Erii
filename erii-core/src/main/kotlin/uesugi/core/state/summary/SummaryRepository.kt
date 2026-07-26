package uesugi.core.state.summary

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryRecord
import uesugi.common.data.HistoryTable
import uesugi.common.data.toRecord
import kotlin.time.Clock

/**
 * 摘要仓库 - 负责摘要相关的数据库操作
 */
class SummaryRepository {
    fun findGroupsNeedProcessing(botMark: String): List<String> = transaction {
        val allGroupIds = HistoryTable
            .select(HistoryTable.groupId)
            .where { HistoryTable.botMark eq botMark }
            .groupBy(HistoryTable.groupId)
            .map { it[HistoryTable.groupId] }
            .distinct()

        allGroupIds.filter { groupId ->
            val summaryState = SummaryStateEntity.find(
                (SummaryStateTable.botMark eq botMark) and (SummaryStateTable.groupId eq groupId)
            ).firstOrNull()

            val lastProcessedId = summaryState?.lastProcessedHistoryId ?: 0
            HistoryEntity.count(
                (HistoryTable.botMark eq botMark) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.id greater lastProcessedId)
            ) > 0
        }
    }

    fun latestHistoryId(botMark: String, groupId: String): Int? = transaction {
        HistoryTable
            .select(HistoryTable.id)
            .where { (HistoryTable.botMark eq botMark) and (HistoryTable.groupId eq groupId) }
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(HistoryTable.id)
            ?.value
    }

    fun getSummaryState(botMark: String, groupId: String): SummaryStateRecord? = transaction {
        SummaryStateEntity.find(
            (SummaryStateTable.botMark eq botMark) and (SummaryStateTable.groupId eq groupId)
        ).firstOrNull()?.toRecord()
    }

    fun getHistoriesToProcess(
        botMark: String,
        groupId: String,
        lastHistoryId: Int,
        limit: Int
    ): List<HistoryRecord> = transaction {
        HistoryEntity.find(
            (HistoryTable.botMark eq botMark) and
                    (HistoryTable.groupId eq groupId) and
                    (HistoryTable.id greater lastHistoryId)
        )
            .orderBy(HistoryTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toRecord() }
    }

    /**
     * 获取最新一批历史消息，并按时间正序返回。
     *
     * 用于尚未建立处理游标的群组：只处理最新窗口，更早历史作为基线数据跳过。
     */
    fun getLatestHistories(botMark: String, groupId: String, limit: Int): List<HistoryRecord> = transaction {
        HistoryEntity.find(
            (HistoryTable.botMark eq botMark) and (HistoryTable.groupId eq groupId)
        )
            .orderBy(HistoryTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toRecord() }
            .asReversed()
    }

    fun updateSummaryState(botMark: String, groupId: String, lastHistoryId: Int) {
        transaction {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val existing = SummaryStateEntity.find(
                (SummaryStateTable.botMark eq botMark) and (SummaryStateTable.groupId eq groupId)
            ).firstOrNull()

            if (existing != null) {
                existing.lastProcessedHistoryId = lastHistoryId
                existing.lastProcessedAt = now
            } else {
                SummaryStateEntity.new {
                    this.botMark = botMark
                    this.groupId = groupId
                    this.lastProcessedHistoryId = lastHistoryId
                    this.lastProcessedAt = now
                }
            }
        }
    }

    /**
     * 保存对话摘要
     */
    fun saveSummary(
        botMark: String,
        groupId: String,
        timeRange: String,
        content: String,
        keyPoints: String,
        emotionalTone: String?,
        participantCount: Int,
        messageCount: Int
    ) {
        transaction {
            SummaryEntity.new {
                this.botMark = botMark
                this.groupId = groupId
                this.timeRange = timeRange
                this.content = content
                this.keyPoints = keyPoints
                this.emotionalTone = emotionalTone
                this.participantCount = participantCount
                this.messageCount = messageCount
            }
        }
    }

    fun getSummariesByGroup(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<SummaryRecord>, Int> = transaction {
        val condition =
            (SummaryTable.botMark eq botMark) and (SummaryTable.groupId eq groupId)
        val baseQuery = SummaryEntity.find { condition }
        val total = baseQuery.count().toInt()
        val query = SummaryTable
            .selectAll()
            .where { condition }
            .orderBy(SummaryTable.createdAt to SortOrder.DESC)
        val pageQuery = if (limit > 0) {
            query.limit(limit).offset(offset.toLong())
        } else {
            query.offset(offset.toLong())
        }
        val items = SummaryEntity.wrapRows(pageQuery).map { it.toRecord() }
        items to total
    }

    /**
     * 获取最近一条摘要，用于生成下一段摘要时提供上下文
     */
    fun getLatestSummary(botMark: String, groupId: String): SummaryRecord? = transaction {
        SummaryEntity.find {
            (SummaryTable.botMark eq botMark) and (SummaryTable.groupId eq groupId)
        }.orderBy(SummaryTable.createdAt to SortOrder.DESC).limit(1).firstOrNull()?.toRecord()
    }

    fun getSummaryById(id: Int): SummaryRecord? = transaction {
        SummaryEntity.findById(id)?.toRecord()
    }

    fun updateSummary(
        id: Int,
        timeRange: String,
        content: String,
        keyPoints: String,
        emotionalTone: String?
    ): SummaryRecord? = transaction {
        SummaryEntity.findById(id)?.apply {
            this.timeRange = timeRange
            this.content = content
            this.keyPoints = keyPoints
            this.emotionalTone = emotionalTone
        }?.toRecord()
    }

    fun deleteSummary(id: Int): Boolean = transaction {
        val summary = SummaryEntity.findById(id)
        summary?.delete()
        summary != null
    }

    /**
     * 删除 createdAt 早于指定时间点的摘要记录
     */
    fun deleteSummariesBefore(cutoff: LocalDateTime): Int = transaction {
        val expired = SummaryEntity.find { SummaryTable.createdAt less cutoff }.toList()
        expired.forEach { it.delete() }
        expired.size
    }
}
