package uesugi.core.cron

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mapdb.HTreeMap
import org.mapdb.Serializer
import uesugi.core.component.storage.MapDB
import uesugi.core.manage.ManageListQuery

@Serializable
private data class CronTaskIds(val ids: Set<String> = emptySet())

@Serializable
private data class BotGroupKeys(val keys: Set<String> = emptySet())

class CronStore {

    companion object {
        private const val KEY_PREFIX = "cron"
        private const val IDS_SUFFIX = "ids"
        private const val BOT_GROUPS_KEY = "cron:bot_groups"
    }

    private val db: HTreeMap<String, String> by lazy {
        MapDB.module("cron").hashMap("core_cron")
            .keySerializer(Serializer.STRING)
            .valueSerializer(Serializer.STRING)
            .createOrOpen()
    }

    private val json = Json

    private fun taskKey(botId: String, groupId: String, taskId: String) =
        "$KEY_PREFIX:$botId:$groupId:$taskId"

    private fun idsKey(botId: String, groupId: String) =
        "$KEY_PREFIX:$botId:$groupId:$IDS_SUFFIX"

    suspend fun saveTask(task: CronTask) = withContext(Dispatchers.IO) {
        db[taskKey(task.botId, task.groupId, task.taskId)] =
            json.encodeToString(CronTask.serializer(), task)

        val idsJson = db[idsKey(task.botId, task.groupId)]
        val ids = if (idsJson != null) {
            json.decodeFromString<CronTaskIds>(idsJson).ids.toMutableSet()
        } else {
            mutableSetOf()
        }
        ids.add(task.taskId)
        db[idsKey(task.botId, task.groupId)] =
            json.encodeToString(CronTaskIds.serializer(), CronTaskIds(ids))

        registerBotGroup(task.botId, task.groupId)
    }

    suspend fun getTask(botId: String, groupId: String, taskId: String): CronTask? =
        withContext(Dispatchers.IO) {
            db[taskKey(botId, groupId, taskId)]?.let {
                json.decodeFromString(CronTask.serializer(), it)
            }
        }

    suspend fun deleteTask(botId: String, groupId: String, taskId: String) =
        withContext(Dispatchers.IO) {
            db.remove(taskKey(botId, groupId, taskId))

            val idsJson = db[idsKey(botId, groupId)]
            if (idsJson != null) {
                val ids = json.decodeFromString<CronTaskIds>(idsJson).ids.toMutableSet()
                ids.remove(taskId)
                db[idsKey(botId, groupId)] =
                    json.encodeToString(CronTaskIds.serializer(), CronTaskIds(ids))
            }
        }

    suspend fun getAllTaskIds(botId: String, groupId: String): Set<String> =
        withContext(Dispatchers.IO) {
            db[idsKey(botId, groupId)]?.let {
                json.decodeFromString<CronTaskIds>(it).ids
            } ?: emptySet()
        }

    suspend fun getAllActiveTasks(botId: String, groupId: String): List<CronTask> {
        val ids = getAllTaskIds(botId, groupId)
        return ids.mapNotNull { id ->
            getTask(botId, groupId, id)?.takeIf { it.status == CronTaskStatus.ACTIVE }
        }
    }

    suspend fun getAllTasks(
        botId: String,
        groupId: String,
        offset: Int = 0,
        limit: Int = 0
    ): Pair<List<CronTask>, Int> = getAllTasks(
        botId,
        groupId,
        ManageListQuery(offset = offset, limit = limit)
    )

    suspend fun getAllTasks(
        botId: String,
        groupId: String,
        listQuery: ManageListQuery
    ): Pair<List<CronTask>, Int> {
        val ids = getAllTaskIds(botId, groupId)
        val search = listQuery.search.lowercase()
        val tasks = ids.mapNotNull { id -> getTask(botId, groupId, id) }
            .filter { task ->
                search.isBlank() || listOf(
                    task.taskId,
                    task.content,
                    task.targetUserId.orEmpty(),
                    task.taskType.name,
                    task.triggerType?.name.orEmpty(),
                    task.status.name
                ).any { it.lowercase().contains(search) }
            }
        val primaryComparator = when (listQuery.sortBy) {
            "taskType" -> compareBy<CronTask> { it.taskType.name }
            "triggerTime" -> compareBy { it.triggerTime }
            "status" -> compareBy { it.status.name }
            "firedAt" -> Comparator<CronTask> { first, second -> compareValues(first.firedAt, second.firedAt) }
            else -> compareBy { it.taskId }
        }
        val comparator = primaryComparator.thenBy { it.taskId }
        val sorted = tasks.sortedWith(if (listQuery.ascending) comparator else comparator.reversed())
        val items = if (listQuery.limit > 0) {
            sorted.drop(listQuery.offset).take(listQuery.limit)
        } else {
            sorted.drop(listQuery.offset)
        }
        return items to tasks.size
    }

    suspend fun getAllBotGroupKeys(): List<BotGroupKey> = withContext(Dispatchers.IO) {
        val json2 = db[BOT_GROUPS_KEY] ?: return@withContext emptyList()
        json.decodeFromString<BotGroupKeys>(json2).keys.map { key ->
            val parts = key.split(":", limit = 2)
            BotGroupKey(parts[0], parts[1])
        }
    }

    private fun registerBotGroup(botId: String, groupId: String) {
        val keysJson = db[BOT_GROUPS_KEY]
        val keys = if (keysJson != null) {
            json.decodeFromString<BotGroupKeys>(keysJson).keys.toMutableSet()
        } else {
            mutableSetOf()
        }
        val keyStr = "$botId:$groupId"
        if (keys.add(keyStr)) {
            db[BOT_GROUPS_KEY] = json.encodeToString(BotGroupKeys.serializer(), BotGroupKeys(keys))
        }
    }
}
