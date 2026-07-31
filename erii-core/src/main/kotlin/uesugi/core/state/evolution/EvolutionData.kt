@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.evolution

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
 * 学习词汇表 - 存储从群聊中学习到的流行语、梗、黑话
 *
 * 用于实现"模因进化系统"：让机器人的语言风格随群聊氛围动态漂移
 *
 * 字段说明：
 * - word: 词汇本身（如"绝绝子"、"泰裤辣"）
 * - type: 词性类型（adjective形容词、exclamation感叹词、noun名词等）
 * - meaning: 词汇含义解释
 * - example: 使用示例
 * - weight: 权重（0-100），影响词汇被选中的概率
 * - lastSeen: 最后出现时间
 * - createdAt: 创建时间
 */
object LearnedVocabTable : IntIdTable("learned_vocab") {
    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)

    val word = varchar("word", 255)
    val type = varchar("type", 50)
    val meaning = text("meaning")
    val example = text("example")

    val weight = integer("weight").default(50)
    val lastSeen = datetime("last_seen").defaultExpression(CurrentDateTime)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

object EvolutionStateTable : IntIdTable("evolution_state") {
    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val lastProcessedHistoryId = integer("last_processed_history_id").default(0)
    val lastProcessedAt = datetime("last_processed_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex(botId, groupId)
    }
}

class EvolutionStateEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EvolutionStateEntity>(EvolutionStateTable)

    var botId by EvolutionStateTable.botId
    var groupId by EvolutionStateTable.groupId
    var lastProcessedHistoryId by EvolutionStateTable.lastProcessedHistoryId
    var lastProcessedAt by EvolutionStateTable.lastProcessedAt
}

data class EvolutionStateRecord(
    val botId: String,
    val groupId: String,
    val lastProcessedHistoryId: Int,
    val lastProcessedAt: LocalDateTime
)

fun EvolutionStateEntity.toStateRecord() = EvolutionStateRecord(
    botId = botId,
    groupId = groupId,
    lastProcessedHistoryId = lastProcessedHistoryId,
    lastProcessedAt = lastProcessedAt
)

data class EvolutionHistoryMessage(val id: Int, val content: String)

/**
 * 学习词汇实体
 */
class LearnedVocabEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LearnedVocabEntity>(LearnedVocabTable)

    var botId by LearnedVocabTable.botId
    var groupId by LearnedVocabTable.groupId
    var word by LearnedVocabTable.word
    var type by LearnedVocabTable.type
    var meaning by LearnedVocabTable.meaning
    var example by LearnedVocabTable.example
    var weight by LearnedVocabTable.weight
    var lastSeen by LearnedVocabTable.lastSeen
    var createdAt by LearnedVocabTable.createdAt
}

/**
 * 学习词汇记录 - 数据传输对象
 */
@Serializable
data class LearnedVocabRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val word: String,
    val type: String,
    val meaning: String,
    val example: String,
    val weight: Int,
    val lastSeen: LocalDateTime,
    val createdAt: LocalDateTime
)

/**
 * 实体转换为记录
 */
fun LearnedVocabEntity.toRecord(): LearnedVocabRecord = LearnedVocabRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    word = word,
    type = type,
    meaning = meaning,
    example = example,
    weight = weight,
    lastSeen = lastSeen,
    createdAt = createdAt
)

/**
 * 流行语/梗/黑话数据结构
 *
 * @property word 词汇本身，如"绝绝子"、"泰裤辣"
 * @property type 词性类型，如 adjective(形容词)、exclamation(感叹词)、noun(名词)
 * @property meaning 词汇的含义解释，如"太好了"
 * @property example 使用示例，如"这奶茶绝绝子"
 */
@Serializable
data class SlangWord(
    val word: String,
    val type: String,
    val meaning: String,
    val example: String
)
