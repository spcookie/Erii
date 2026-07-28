package uesugi.core.message.resource

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uesugi.common.data.HistoryTable
import uesugi.common.data.ResourceEntity
import uesugi.common.data.ResourceRecord
import uesugi.common.data.ResourceTable
import uesugi.common.data.toRecord
import uesugi.core.manage.ManageListQuery

class ResourceService {

    fun saveResource(resource: ResourceRecord): ResourceRecord {
        return transaction {
            ResourceEntity.new {
                botMark = resource.botMark
                groupId = resource.groupId
                url = resource.url
                fileName = resource.fileName
                size = resource.size
                md5 = resource.md5
                createdAt = resource.createdAt
            }.toRecord()
        }
    }

    fun getResource(id: Int): ResourceRecord? {
        return transaction {
            ResourceEntity.findById(id)?.toRecord()
        }
    }

    fun getAllResourcesByGroup(
        botMark: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 500
    ): Pair<List<ResourceRecord>, Int> = getAllResourcesByGroup(
        botMark,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    fun getAllResourcesByGroup(
        botMark: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<ResourceRecord>, Int> {
        return transaction {
            var condition: Op<Boolean> =
                (ResourceTable.botMark eq botMark) and (ResourceTable.groupId eq groupId)
            if (listQuery.search.isNotBlank()) {
                condition = condition and (
                        (ResourceTable.fileName.lowerCase() like listQuery.searchPattern) or
                                (ResourceTable.md5.lowerCase() like listQuery.searchPattern) or
                                (ResourceTable.url.lowerCase() like listQuery.searchPattern)
                        )
            }
            val total = ResourceEntity.find { condition }.count().toInt()
            val query = ResourceTable
                .selectAll()
                .where { condition }
            when (listQuery.sortBy) {
                "id" -> query.orderBy(ResourceTable.id to listQuery.sortOrder)
                "fileName" -> query.orderBy(ResourceTable.fileName to listQuery.sortOrder, ResourceTable.id to listQuery.sortOrder)
                "size" -> query.orderBy(ResourceTable.size to listQuery.sortOrder, ResourceTable.id to listQuery.sortOrder)
                else -> query.orderBy(ResourceTable.createdAt to SortOrder.DESC, ResourceTable.id to SortOrder.DESC)
            }
            val pageQuery = if (listQuery.limit > 0) {
                query.limit(listQuery.limit).offset(listQuery.offset.toLong())
            } else {
                query.offset(listQuery.offset.toLong())
            }
            val items = ResourceEntity.wrapRows(pageQuery)
                .map { it.toRecord() }
            items to total
        }
    }

    fun deleteResource(id: Int): Boolean {
        return transaction {
            val entity = ResourceEntity.findById(id) ?: return@transaction false
            HistoryTable.update({ HistoryTable.resourceId eq id }) {
                it[resourceId] = null
            }
            entity.delete()
            true
        }
    }

    fun findResourcesOlderThan(cutoff: LocalDateTime, limit: Int = 100, offset: Long = 0): List<ResourceRecord> {
        return transaction {
            ResourceEntity.find { ResourceTable.createdAt less cutoff }
                .orderBy(ResourceTable.createdAt to SortOrder.ASC, ResourceTable.id to SortOrder.ASC)
                .limit(limit)
                .offset(offset)
                .map { it.toRecord() }
        }
    }

}
