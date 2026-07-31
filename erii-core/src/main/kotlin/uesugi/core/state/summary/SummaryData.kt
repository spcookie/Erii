@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.summary

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import uesugi.common.toolkit.LocalDateTimeAsDateSerializer
import uesugi.core.state.emotion.EmotionTable.DEFAULT_LENGTH

/**
 * 对话摘要表 - 存储群聊的摘要信息
 *
 * 字段说明：
 * - timeRange: 时间范围（格式 yyyy-MM-dd HH:mm）
 * - content: 摘要内容（讨论背景、核心冲突/观点、结论）
 * - keyPoints: 关键要点（3-5个信息胶囊）
 * - emotionalTone: 情感基调（焦虑、欢快、激烈争论等）
 * - participantCount: 参与人数
 * - messageCount: 消息总数
 */
object SummaryTable : IntIdTable("memory_summary") {
    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val timeRange = varchar("time_range", length = 50)
    val content = text("content")
    val keyPoints = text("key_points")
    val emotionalTone = varchar("emotional_tone", length = 100).nullable()
    val participantCount = integer("participant_count").default(0)
    val messageCount = integer("message_count").default(0)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

object SummaryStateTable : IntIdTable("summary_state") {
    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val lastProcessedHistoryId = integer("last_processed_history_id").default(0)
    val lastProcessedAt = datetime("last_processed_at")
        .defaultExpression(CurrentDateTime)
}

/**
 * 对话摘要实体
 */
class SummaryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SummaryEntity>(SummaryTable)

    var botId by SummaryTable.botId
    var groupId by SummaryTable.groupId
    var timeRange by SummaryTable.timeRange
    var content by SummaryTable.content
    var keyPoints by SummaryTable.keyPoints
    var emotionalTone by SummaryTable.emotionalTone
    var participantCount by SummaryTable.participantCount
    var messageCount by SummaryTable.messageCount
    var createdAt by SummaryTable.createdAt
}

class SummaryStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SummaryStateEntity>(SummaryStateTable)

    var botId by SummaryStateTable.botId
    var groupId by SummaryStateTable.groupId
    var lastProcessedHistoryId by SummaryStateTable.lastProcessedHistoryId
    var lastProcessedAt by SummaryStateTable.lastProcessedAt
}

/**
 * 对话摘要记录 - 数据传输对象
 */
@Serializable
data class SummaryRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val timeRange: String,
    val content: String,
    val keyPoints: String,
    val emotionalTone: String?,
    val participantCount: Int,
    val messageCount: Int,
    val createdAt: LocalDateTime
)

@Serializable
data class SummaryStateRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val lastProcessedHistoryId: Int,
    val lastProcessedAt: LocalDateTime
)

/**
 * 实体转换为记录
 */
fun SummaryEntity.toRecord(): SummaryRecord = SummaryRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    timeRange = timeRange,
    content = content,
    keyPoints = keyPoints,
    emotionalTone = emotionalTone,
    participantCount = participantCount,
    messageCount = messageCount,
    createdAt = createdAt
)

fun SummaryStateEntity.toRecord(): SummaryStateRecord = SummaryStateRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    lastProcessedHistoryId = lastProcessedHistoryId,
    lastProcessedAt = lastProcessedAt
)
