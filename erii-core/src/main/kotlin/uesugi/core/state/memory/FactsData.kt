@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import uesugi.common.toolkit.LocalDateTimeAsDateSerializer
import uesugi.core.state.emotion.EmotionTable.DEFAULT_LENGTH
import java.util.ArrayList
import kotlin.time.Clock

/**
 * 事实记忆表 - 存储从群聊中提取的事实信息
 *
 * 字段说明：
 * - keyword: 关键词，用于快速检索
 * - description: 事实的自然语言描述
 * - entities: 涉及的实体名称列表（如地点、组织、人物等），JSON 数组存储
 * - subjects: 涉及的主体（用户ID列表）
 * - scopeType: 作用范围（个人/群组）
 * - validFrom/validTo: 有效时间范围（用于实现事实的动态更新）
 */

private val entityJson = Json { ignoreUnknownKeys = true }

object EntityListColumnType : ColumnType<List<String>>() {
    override fun sqlType() = "TEXT"

    override fun valueFromDB(value: Any): List<String> = when (value) {
        is String -> if (value.isBlank()) emptyList() else entityJson.decodeFromString(value)
        is Iterable<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    override fun notNullValueToDB(value: List<String>): Any =
        entityJson.encodeToString(ArrayList(value))

    override fun nonNullValueToString(value: List<String>): String {
        val encoded = notNullValueToDB(value).toString().replace("'", "''")
        return "'$encoded'"
    }
}

object FactsTable : IntIdTable("memory_facts") {
    val botMark = varchar("bot_mark", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val keyword = varchar("keyword", 255)
    val description = text("description")
    val entities = registerColumn("entities", EntityListColumnType).default(emptyList())
    val subjects = text("subjects")
    val scopeType = enumerationByName<Scopes>("scope_type", 50)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val validFrom = datetime("valid_from").defaultExpression(CurrentDateTime)
    val validTo = datetime("valid_to").nullable()
    val lastRecalledAt = datetime("last_recalled_at").nullable()
    val vectorId = varchar("vector_id", length = 64).nullable()

    fun validCondition(botMark: String, groupId: String) =
        (this.botMark eq botMark) and
                (this.groupId eq groupId) and
                (this.validFrom lessEq CurrentDateTime) and
                (this.validTo.isNull() or (this.validTo greater CurrentDateTime))
}

/**
 * 记忆作用范围枚举
 */
@Serializable(with = ScopesSerializer::class)
enum class Scopes {
    @LLMDescription("个人属性 USER")
    USER,

    @LLMDescription("群组共识 GROUP")
    GROUP
}

object ScopesSerializer : KSerializer<Scopes> {
    override val descriptor = PrimitiveSerialDescriptor("Scopes", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Scopes {
        return when (decoder.decodeString().lowercase()) {
            "user" -> Scopes.USER
            "group" -> Scopes.GROUP
            else -> throw IllegalArgumentException("Unknown scope: ${decoder.decodeString()}")
        }
    }

    override fun serialize(encoder: Encoder, value: Scopes) {
        encoder.encodeString(value.name.lowercase())
    }
}

/**
 * 事实记忆实体
 */
class FactsEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<FactsEntity>(FactsTable)

    var botMark by FactsTable.botMark
    var groupId by FactsTable.groupId
    var keyword by FactsTable.keyword
    var description by FactsTable.description
    var entities by FactsTable.entities
    var subjects by FactsTable.subjects
    var scopeType by FactsTable.scopeType
    var createdAt by FactsTable.createdAt
    var validFrom by FactsTable.validFrom
    var validTo by FactsTable.validTo
    var lastRecalledAt by FactsTable.lastRecalledAt
    var vectorId by FactsTable.vectorId
}

fun FactsEntity.isVisibleTo(subjects: List<String>): Boolean =
    scopeType == Scopes.GROUP ||
            this.subjects.split(",").map { it.trim() }.any { it in subjects }

/**
 * 事实记忆记录 - 数据传输对象
 */
@Serializable
data class FactsRecord(
    val id: Int,
    val botMark: String,
    val groupId: String,
    val keyword: String,
    val description: String,
    val entities: List<String>,
    val subjects: String,
    val scopeType: Scopes,
    val createdAt: LocalDateTime,
    val validFrom: LocalDateTime,
    val validTo: LocalDateTime?,
    val lastRecalledAt: LocalDateTime? = null,
    val vectorId: String? = null,
    val valid: Boolean
)

@Serializable
data class MemoryFactSearchResult(
    val fact: FactsRecord,
    val score: Float? = null,
    val vectorId: String? = null,
    val source: String
)

@Serializable
data class MemoryGraphNode(
    val id: String,
    val type: String,
    val label: String,
    val source: String = ""
)

@Serializable
data class MemoryGraphEdge(
    val from: String,
    val to: String,
    val label: String
)

@Serializable
data class MemoryVectorSearchResponse(
    val query: String,
    val results: List<MemoryFactSearchResult>
)

@Serializable
data class MemoryGraphSearchResponse(
    val query: String,
    val seedResults: List<MemoryFactSearchResult>,
    val expandedResults: List<MemoryFactSearchResult>,
    val nodes: List<MemoryGraphNode>,
    val edges: List<MemoryGraphEdge>
)

// ==================== 事实提取与冲突解决数据模型 ====================

/**
 * LLM 提取的原始事实
 */
@Serializable
@LLMDescription("从消息中提取的事实")
data class ExtractedFact(
    @property:LLMDescription("关键词，2-6个字")
    val keyword: String,
    @property:LLMDescription("事实的自然语言描述，20-50字")
    val description: String,
    @property:LLMDescription("事实涉及的实体名称列表，如 [\"杭州\", \"重庆\"]")
    val entities: List<String> = emptyList(),
    @property:LLMDescription("涉及的用户ID，逗号分隔")
    val subjects: String = "",
    @property:LLMDescription("范围类型: user 或 group")
    val scope: Scopes = Scopes.USER
)

/**
 * 事实提取结果
 */
@Serializable
@LLMDescription("事实提取结果")
data class FactExtractionResult(
    @property:LLMDescription("提取出的事实列表")
    val facts: List<ExtractedFact>
)

/**
 * 记忆操作类型
 */
@Serializable(with = MemoryActionSerializer::class)
enum class MemoryAction {
    @LLMDescription("添加新事实")
    ADD,

    @LLMDescription("废弃过时事实")
    DELETE,

    @LLMDescription("无需操作")
    NONE
}

object MemoryActionSerializer : KSerializer<MemoryAction> {
    override val descriptor = PrimitiveSerialDescriptor("MemoryAction", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MemoryAction {
        return when (val value = decoder.decodeString().lowercase()) {
            "add" -> MemoryAction.ADD
            "delete" -> MemoryAction.DELETE
            "none" -> MemoryAction.NONE
            else -> throw IllegalArgumentException("Unknown action: $value")
        }
    }

    override fun serialize(encoder: Encoder, value: MemoryAction) {
        encoder.encodeString(value.name.lowercase())
    }
}

/**
 * 单条冲突解决决策
 */
@Serializable
@LLMDescription("记忆冲突解决决策")
data class MemoryDecision(
    @property:LLMDescription("操作类型: ADD/DELETE/NONE")
    val action: MemoryAction,
    @property:LLMDescription("新提取的事实（ADD 时必填）")
    val newFact: ExtractedFact? = null,
    @property:LLMDescription("已有事实的ID（DELETE 时必填）")
    val existingFactId: Int? = null,
    @property:LLMDescription("决策原因")
    val reason: String = ""
)

/**
 * 冲突解决结果
 */
@Serializable
@LLMDescription("冲突解决结果")
data class ConflictResolutionResult(
    @property:LLMDescription("所有决策列表")
    val decisions: List<MemoryDecision>
)

/**
 * 实体转换为记录
 */
fun FactsEntity.toRecord(): FactsRecord = FactsRecord(
    id = id.value,
    botMark = botMark,
    groupId = groupId,
    keyword = keyword,
    description = description,
    entities = entities,
    subjects = subjects,
    scopeType = scopeType,
    createdAt = createdAt,
    validFrom = validFrom,
    validTo = validTo,
    lastRecalledAt = lastRecalledAt,
    vectorId = vectorId,
    valid = isCurrentlyValid()
)

private fun FactsEntity.isCurrentlyValid(): Boolean {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return validFrom <= now && (validTo == null || validTo!! > now)
}
