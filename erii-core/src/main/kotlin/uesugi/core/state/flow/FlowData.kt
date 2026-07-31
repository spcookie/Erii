@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.flow

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
 * 心流状态表 - 记录群组的对话心流状态
 *
 * 字段说明：
 * - currentTopic: 当前话题
 * - flowValue: 心流值（0.0-100.0），表示对话的流畅度和参与度
 * - lastUpdateTime: 最后更新时间戳
 * - lastProcessedHistoryId: 最后处理的 history ID
 * - lastProcessedAt: 最后处理时间
 *
 * 处理逻辑：
 * 1. 分析对话的流畅度、话题连续性、参与者活跃度
 * 2. 计算心流值，用于判断是否需要主动引导话题
 * 3. 检测话题切换时机
 */
object FlowStateTable : IntIdTable("flow_state") {
    val botId = varchar("bot_id", length = 64)
    val groupId = varchar("group_id", length = 64)
    val lastProcessedHistoryId = integer("last_processed_history_id").default(0)
    val lastProcessedAt = datetime("last_processed_at")
        .defaultExpression(CurrentDateTime)
    val currentTopic = text("current_topic").default("")
    val flowValue = double("flow_value").default(0.0)
    val lastUpdateTime = long("last_update_time").default(0)
}

/**
 * 心流状态实体
 */
class FlowStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<FlowStateEntity>(FlowStateTable)

    var botId by FlowStateTable.botId
    var groupId by FlowStateTable.groupId
    var lastProcessedHistoryId by FlowStateTable.lastProcessedHistoryId
    var lastProcessedAt by FlowStateTable.lastProcessedAt
    var currentTopic by FlowStateTable.currentTopic
    var flowValue by FlowStateTable.flowValue
    var lastUpdateTime by FlowStateTable.lastUpdateTime
}

/**
 * 心流状态记录 - 数据传输对象
 */
@Serializable
data class FlowStateRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val lastProcessedHistoryId: Int,
    val lastProcessedAt: LocalDateTime,
    val currentTopic: String,
    val flowValue: Double,
    val lastUpdateTime: Long
)

/**
 * 实体转换为记录
 */
fun FlowStateEntity.toRecord(): FlowStateRecord = FlowStateRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    lastProcessedHistoryId = lastProcessedHistoryId,
    lastProcessedAt = lastProcessedAt,
    currentTopic = currentTopic,
    flowValue = flowValue,
    lastUpdateTime = lastUpdateTime
)
