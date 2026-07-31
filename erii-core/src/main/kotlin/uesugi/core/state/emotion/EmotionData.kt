package uesugi.core.state.emotion

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.json
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.HistoryEntity
import uesugi.common.data.HistoryTable
import uesugi.common.data.PAD
import uesugi.common.toolkit.JSON

object EmotionTable : IntIdTable("chat_emotion") {
    const val DEFAULT_LENGTH = 64

    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val emotionalTendency =
        enumerationByName<EmotionalTendencies>("emotional_tendency", length = 32)
    val stimulus = varchar("stimulus", length = 255)
    val emotion = varchar("emotion", length = 255)
    val mood = varchar("mood", length = 255)
    val behavior = json<BehaviorProfile>("behavior", JSON)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val historyMessageProcessed = integer("history_message_processed").default(0)

}

class EmotionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EmotionEntity>(EmotionTable)

    var botId by EmotionTable.botId
    var groupId by EmotionTable.groupId
    var emotionalTendency by EmotionTable.emotionalTendency
    var stimulus: PAD by EmotionTable.stimulus.transform(PADColumnTransformer())
    var emotion by EmotionTable.emotion.transform(PADColumnTransformer())
    var mood by EmotionTable.mood.transform(PADColumnTransformer())
    var behavior by EmotionTable.behavior
    var updatedAt by EmotionTable.updatedAt
    var createdAt by EmotionTable.createdAt
    var historyMessageProcessed by EmotionTable.historyMessageProcessed
}

private class PADColumnTransformer : ColumnTransformer<String, PAD> {
    override fun unwrap(value: PAD): String {
        return "${value.p},${value.a},${value.d}"
    }

    override fun wrap(value: String): PAD {
        val dimensions = value.split(",").map { s -> s.trim().toDouble() }
        return if (dimensions.size >= 3) {
            PAD(p = dimensions[0], a = dimensions[1], d = dimensions[2])
        } else {
            throw IllegalArgumentException("Invalid PAD format: $value")
        }
    }

}


typealias Stimulus = PAD
typealias Emotion = PAD
typealias Mood = PAD

enum class Retention {
    HIGH,
    MEDIUM,
    LOW
}


@Serializable
data class BehaviorProfile(
    val emotion: EmotionalTendencies,
    val tone: Tone,
    val aggressiveness: Aggressiveness,
    val emojiLevel: EmojiLevel
)

enum class Tone {
    FRIENDLY,
    GENTLE,
    NEUTRAL,
    IRONIC,
    LOW_ENERGY
}

enum class Aggressiveness {
    NONE,
    ABSTRACT_SARCASM,
    TEASING
}

enum class EmojiLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

fun EmotionEntity.Companion.findRequiredAnalysisHistoryGroupIds(botId: String): List<String> {
    return transaction {
        // 获取每个群组的最新 historyMessageProcessed
        val lastProcessedByGroup = EmotionTable
            .select(EmotionTable.groupId, EmotionTable.historyMessageProcessed)
            .where { EmotionTable.botId eq botId }
            .map { it[EmotionTable.groupId] to it[EmotionTable.historyMessageProcessed] }
            .groupBy { it.first }
            .mapValues { it.value.maxOfOrNull { p -> p.second } ?: 0 }

        // 获取所有有历史消息的 groupId
        val allGroupIds = HistoryTable
            .select(HistoryTable.groupId)
            .where { HistoryTable.botId eq botId }
            .withDistinct(true)
            .map { it[HistoryTable.groupId] }

        // 对每个 groupId 统计未处理消息数
        allGroupIds.filter { groupId ->
            val lastProcessed = lastProcessedByGroup[groupId] ?: 0
            val newCount = HistoryEntity.count(
                (HistoryTable.botId eq botId) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.id greater lastProcessed)
            )
            newCount > 0
        }
    }
}

fun EmotionEntity.Companion.findNotAnalysisHistoryGroupIds(
    botId: String,
    groupIds: List<String>
): List<String> {
    return transaction {
        EmotionTable.select(EmotionTable.groupId)
            .where {
                (EmotionTable.botId eq botId).apply {
                    if (groupIds.isNotEmpty()) {
                        this and (EmotionTable.groupId notInList groupIds)
                    }
                }
            }.withDistinct(true)
            .map {
                it[EmotionTable.groupId]
            }
    }
}

@Serializable
data class EmotionRecord(
    val id: Int,
    val botId: String,
    val groupId: String,
    val emotionalTendency: String,
    val stimulus: PAD,
    val emotion: PAD,
    val mood: PAD,
    val behavior: BehaviorProfile,
    val historyMessageProcessed: Int
)

fun EmotionEntity.toRecord(): EmotionRecord = EmotionRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    emotionalTendency = emotionalTendency.name,
    stimulus = stimulus,
    emotion = emotion,
    mood = mood,
    behavior = behavior,
    historyMessageProcessed = historyMessageProcessed
)
