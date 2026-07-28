package uesugi.core.message.history

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.*
import uesugi.core.manage.ManageListQuery
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class HourlyMessageCount(
    val hourLabel: String,
    val botCount: Int,
    val groupCount: Int
)

class HistoryService {
    fun getLatestHistory(botMark: String, groupId: String, limit: Int, range: Duration): List<HistoryRecord> {
        val now = Clock.System.now()
        val oneDayAgo = now - range
        val timeZone = TimeZone.currentSystemDefault()
        return transaction {
            HistoryEntity.find {
                (HistoryTable.botMark eq botMark) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.createdAt greaterEq oneDayAgo.toLocalDateTime(timeZone)) and
                        (HistoryTable.createdAt lessEq now.toLocalDateTime(timeZone))
            }.orderBy(HistoryTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .reversed()
                .toList()
                .map {
                    it.toRecord()
                }
        }
    }

    fun saveHistory(history: HistoryRecord): HistoryRecord {
        return transaction {
            HistoryEntity.new {
                this.botMark = history.botMark
                this.groupId = history.groupId
                this.userId = history.userId
                this.nick = history.nick
                this.messageType = history.messageType
                this.resource = history.resource?.let { resource ->
                    ResourceEntity.new {
                        this.botMark = history.botMark
                        this.groupId = history.groupId
                        this.url = resource.url
                        this.fileName = resource.fileName
                        this.size = resource.size
                        this.md5 = resource.md5
                        this.createdAt = resource.createdAt
                    }
                }
                this.content = history.content
            }.toRecord()
        }
    }

    fun getAllHistoryByGroup(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 500
    ): Pair<List<HistoryRecord>, Int> = getAllHistoryByGroup(
        botMark,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    fun getAllHistoryByGroup(
        botMark: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<HistoryRecord>, Int> {
        return transaction {
            var condition: Op<Boolean> =
                (HistoryTable.botMark eq botMark) and (HistoryTable.groupId eq groupId)
            if (listQuery.search.isNotBlank()) {
                val matchingTypes = MessageType.entries.filter {
                    it.name.lowercase().contains(listQuery.search.lowercase())
                }
                var searchCondition: Op<Boolean> =
                        (HistoryTable.userId.lowerCase() like listQuery.searchPattern) or
                                (HistoryTable.nick.lowerCase() like listQuery.searchPattern) or
                                (HistoryTable.content.lowerCase() like listQuery.searchPattern)
                if (matchingTypes.isNotEmpty()) {
                    searchCondition = searchCondition or (HistoryTable.messageType inList matchingTypes)
                }
                condition = condition and searchCondition
            }
            val total = HistoryEntity.find { condition }.count().toInt()
            val query = HistoryTable
                .selectAll()
                .where { condition }
            when (listQuery.sortBy) {
                "id" -> query.orderBy(HistoryTable.id to listQuery.sortOrder)
                "nick" -> query.orderBy(HistoryTable.nick to listQuery.sortOrder, HistoryTable.id to listQuery.sortOrder)
                "messageType" -> query.orderBy(HistoryTable.messageType to listQuery.sortOrder, HistoryTable.id to listQuery.sortOrder)
                "createdAt" -> query.orderBy(HistoryTable.createdAt to listQuery.sortOrder, HistoryTable.id to listQuery.sortOrder)
                else -> query.orderBy(HistoryTable.createdAt to SortOrder.DESC, HistoryTable.id to SortOrder.DESC)
            }
            val pageQuery = if (listQuery.limit > 0) {
                query.limit(listQuery.limit).offset(listQuery.offset.toLong())
            } else {
                query.offset(listQuery.offset.toLong())
            }
            val items = HistoryEntity.wrapRows(pageQuery)
                .map { it.toRecord() }
            items to total
        }
    }

    fun getHistoryById(id: Int): HistoryRecord? {
        return transaction {
            HistoryEntity.findById(id)?.toRecord()
        }
    }

    fun updateHistory(id: Int, content: String?, nick: String?): HistoryRecord? {
        return transaction {
            val entity = HistoryEntity.findById(id) ?: return@transaction null
            content?.let { entity.content = it }
            nick?.let { entity.nick = it }
            entity.toRecord()
        }
    }

    fun deleteHistory(id: Int): Boolean {
        return transaction {
            val entity = HistoryEntity.findById(id) ?: return@transaction false
            entity.delete()
            true
        }
    }

    fun getHistoryByGroupCursor(
        botMark: String,
        groupId: String,
        beforeId: Int?,
        limit: Int
    ): Pair<List<HistoryRecord>, Boolean> {
        return transaction {
            val baseQuery = if (beforeId != null) {
                HistoryEntity.find {
                    (HistoryTable.botMark eq botMark) and
                            (HistoryTable.groupId eq groupId) and
                            (HistoryTable.id less beforeId)
                }
            } else {
                HistoryEntity.find {
                    (HistoryTable.botMark eq botMark) and (HistoryTable.groupId eq groupId)
                }
            }
            val items = baseQuery
                .orderBy(HistoryTable.id to SortOrder.DESC)
                .limit(limit + 1)
                .toList()
                .map { it.toRecord() }
            val hasMore = items.size > limit
            val result = if (hasMore) items.dropLast(1) else items
            result to hasMore
        }
    }

    fun getHourlyMessageCounts(botMark: String, groupId: String, hours: Int = 12): List<HourlyMessageCount> {
        val now = Clock.System.now()
        val startTime = now - hours.toDuration(DurationUnit.HOURS)
        val timeZone = TimeZone.currentSystemDefault()

        return transaction {
            HistoryEntity.find {
                (HistoryTable.botMark eq botMark) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.createdAt greaterEq startTime.toLocalDateTime(timeZone))
            }.toList()
        }.groupBy { record ->
            val dt = record.createdAt
            dt.hour
        }.let { grouped ->
            val nowLdt = now.toLocalDateTime(timeZone)
            val currentHour = nowLdt.hour
            (0 until hours).map { offset ->
                val hour = (currentHour - (hours - 1 - offset) + 24) % 24
                val records = grouped[hour] ?: emptyList()
                val botCount = records.count { it.userId == botMark }
                val groupCount = records.count { it.userId != botMark }
                HourlyMessageCount(
                    hourLabel = "${hour.toString().padStart(2, '0')}:00",
                    botCount = botCount,
                    groupCount = groupCount
                )
            }
        }
    }
}
