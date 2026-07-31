@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.core.state.memory

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
 * 用户画像表 - 存储用户的行为特征和偏好
 *
 * 字段说明：
 * - profile: 用户画像（性格、行为模式、角色特征）
 * - preferences: 兴趣偏好（技术领域、讨论深度等）
 * - createdAt: 创建时间
 */
object UserProfileTable : IntIdTable("memory_user_profile") {
    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)
    val userId = varchar("user_id", length = 64)
    val profile = text("profile")
    val preferences = text("preferences")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

/**
 * 用户画像实体
 */
class UserProfileEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserProfileEntity>(UserProfileTable)

    var botId by UserProfileTable.botId
    var groupId by UserProfileTable.groupId
    var userId by UserProfileTable.userId
    var profile by UserProfileTable.profile
    var preferences by UserProfileTable.preferences
    var createdAt by UserProfileTable.createdAt
}

/**
 * 用户画像记录 - 数据传输对象
 */
@Serializable
data class UserProfileRecord(
    var id: Int,
    var botId: String,
    var groupId: String,
    var userId: String,
    var profile: String,
    var preferences: String,
    var createdAt: LocalDateTime
)

/**
 * 实体转换为记录
 */
fun UserProfileEntity.toRecord(): UserProfileRecord = UserProfileRecord(
    id = id.value,
    botId = botId,
    groupId = groupId,
    userId = userId,
    profile = profile,
    preferences = preferences,
    createdAt = createdAt
)
