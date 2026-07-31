@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.volition

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

/**
 * 意愿状态表 - 记录群组的意愿状态
 *
 * 字段说明：
 * - fatigue: 疲劳度（0.0-100.0），表示机器人的参与疲劳程度
 * - stimulus: 刺激值（0.0-100.0），用于触发主动行为
 * - lastActiveTime: 最后活跃时间戳
 * - lastProcessedHistoryId: 最后处理的 history ID
 * - lastProcessedAt: 最后处理时间
 *
 * 处理逻辑：
 * 1. 定时更新意愿状态，根据消息频率和上下文计算疲劳度和刺激值
 * 2. 刺激值超过阈值时触发主动行为
 * 3. 疲劳度达到上限时抑制主动行为
 */
object VolitionStateTable : IntIdTable("volition_state") {
    val botId = varchar("bot_id", length = 64)
    val groupId = varchar("group_id", length = 64)
    val fatigue = double("fatigue").default(0.0)
    val stimulus = double("stimulus").default(0.0)
    val lastActiveTime = long("last_active_time").default(0)
    val lastProcessedHistoryId = integer("last_processed_history_id").default(0)
    val lastProcessedAt = datetime("last_processed_at")
        .defaultExpression(CurrentDateTime)
}

/**
 * 意愿状态实体
 */
class VolitionStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<VolitionStateEntity>(VolitionStateTable)

    var botId by VolitionStateTable.botId
    var groupId by VolitionStateTable.groupId
    var fatigue by VolitionStateTable.fatigue
    var stimulus by VolitionStateTable.stimulus
    var lastActiveTime by VolitionStateTable.lastActiveTime
    var lastProcessedHistoryId by VolitionStateTable.lastProcessedHistoryId
    var lastProcessedAt by VolitionStateTable.lastProcessedAt
}

/**
 * 意愿状态记录 - 数据传输对象
 */
@Serializable
data class VolitionStateRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val fatigue: Double,
    val stimulus: Double,
    val lastActiveTime: Long,
    val lastProcessedHistoryId: Int,
    val lastProcessedAt: LocalDateTime
)

/**
 * 实体转换为记录
 */
fun VolitionStateEntity.toRecord(): VolitionStateRecord = VolitionStateRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    fatigue = fatigue,
    stimulus = stimulus,
    lastActiveTime = lastActiveTime,
    lastProcessedHistoryId = lastProcessedHistoryId,
    lastProcessedAt = lastProcessedAt
)
