@file:UseSerializers(LocalDateTimeAsDateSerializer::class)

package uesugi.common.data

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import uesugi.common.toolkit.LocalDateTimeAsDateSerializer

object ResourceTable : IntIdTable("chat_resource") {
    const val DEFAULT_LENGTH = 255

    val botId = varchar("bot_id", length = DEFAULT_LENGTH)
    val groupId = varchar("group_id", length = DEFAULT_LENGTH)

    val url = varchar("url", length = 1024)

    val fileName = varchar("file_name", length = DEFAULT_LENGTH)

    val size = long("size")

    val md5 = varchar("md5", length = DEFAULT_LENGTH)

    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class ResourceEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ResourceEntity>(ResourceTable)

    var botId by ResourceTable.botId
    var groupId by ResourceTable.groupId
    var url by ResourceTable.url
    var fileName by ResourceTable.fileName
    var size by ResourceTable.size
    var md5 by ResourceTable.md5
    val histories by HistoryEntity optionalReferrersOn HistoryTable.resourceId
    var createdAt by ResourceTable.createdAt

}

@Serializable
data class ResourceRecord(
    val id: Int? = null,
    val botId: String,
    val groupId: String,
    val url: String,
    val fileName: String,
    val size: Long,
    val md5: String,
    val histories: List<HistoryRecord>? = null,
    val createdAt: LocalDateTime,
    @Transient var bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResourceRecord) return false

        if (id != other.id) return false
        if (size != other.size) return false
        if (botId != other.botId) return false
        if (groupId != other.groupId) return false
        if (url != other.url) return false
        if (fileName != other.fileName) return false
        if (md5 != other.md5) return false
        if (histories != other.histories) return false
        if (createdAt != other.createdAt) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id ?: 0
        result = 31 * result + size.hashCode()
        result = 31 * result + botId.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + md5.hashCode()
        result = 31 * result + (histories?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}
