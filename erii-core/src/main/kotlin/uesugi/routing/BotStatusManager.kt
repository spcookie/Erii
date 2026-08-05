package uesugi.routing

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import uesugi.common.BotManage
import uesugi.common.data.Channel
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.PAD
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.cron.CronService
import uesugi.core.cron.CronTaskStatus
import uesugi.core.manage.ManageListQuery
import uesugi.core.message.history.HistoryService
import uesugi.core.message.resource.ResourceService
import uesugi.core.state.emotion.BehaviorProfile
import uesugi.core.state.emotion.EmotionRepository
import uesugi.core.state.emotion.EmotionService
import uesugi.core.state.emotion.toRecord
import uesugi.core.state.evolution.EvolutionService
import uesugi.core.state.evolution.SlangWord
import uesugi.core.state.evolution.toRecord
import uesugi.core.state.flow.FlowGaugeManager
import uesugi.core.state.flow.FlowRepository
import uesugi.core.state.flow.toRecord
import uesugi.core.state.meme.MemeData.MemeVectorSearchResponse
import uesugi.core.state.meme.MemeData.MemeVectorSearchResult
import uesugi.core.state.meme.MemeRepository
import uesugi.core.state.meme.MemeService
import uesugi.core.state.memory.MemoryRepository
import uesugi.core.state.memory.MemoryService
import uesugi.core.state.memory.Scopes
import uesugi.core.state.memory.toRecord
import uesugi.core.state.summary.SummaryService
import uesugi.core.state.volition.VolitionGaugeManager
import uesugi.core.state.volition.VolitionRepository
import uesugi.core.state.volition.toRecord
import uesugi.onebot.sdk.client.api.getGroupList
import uesugi.onebot.sdk.client.api.getStrangerInfo

@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

@Serializable
data class FactRequest(
    val keyword: String,
    val description: String,
    val entities: List<String> = emptyList(),
    val subjects: String,
    val scopeType: Scopes
)

@Serializable
data class MemorySearchRequest(
    val query: String,
    val limit: Int = 10
)

@Serializable
data class MemeSearchRequest(
    val query: String,
    val limit: Int = 10
)

@Serializable
data class UpdateUserProfileRequest(
    val profile: String,
    val preferences: String
)

@Serializable
data class UpdateMemeRequest(
    val description: String? = null,
    val purpose: String? = null,
    val tags: String? = null
)

@Serializable
data class VocabularyRequest(
    val word: String,
    val type: String,
    val meaning: String,
    val example: String,
    val weight: Int = 50
)

@Serializable
data class UpdateSummaryRequest(
    val timeRange: String,
    val content: String,
    val keyPoints: String,
    val emotionalTone: String? = null
)

@Serializable
data class UpdateHistoryRequest(
    val content: String? = null,
    val nick: String? = null
)

@Serializable
data class UpdateEmotionRequest(
    val emotionalTendency: String,
    val stimulus: PAD,
    val emotion: PAD,
    val mood: PAD,
    val behavior: BehaviorProfile
)

@Serializable
data class UpdateFlowRequest(
    val flowValue: Double,
    val currentTopic: String
)

@Serializable
data class UpdateVolitionRequest(
    val fatigue: Double,
    val stimulus: Double
)

@Serializable
data class UpdateCronTaskRequest(
    val content: String? = null,
    val triggerTime: Long? = null,
    val targetUserId: String? = null,
    val cronExpression: String? = null,
    val status: String? = null
)

private fun ApplicationCall.botId(): String = parameters["bot-id"]!!
private fun ApplicationCall.groupId(): String = parameters["group-id"]!!
private fun ApplicationCall.userId(): String = parameters["user-id"]!!
private fun ApplicationCall.intPathParam(name: String): Int? = parameters[name]?.toIntOrNull()
private fun ApplicationCall.manageListQuery(defaultLimit: Int = 50): ManageListQuery {
    val offset = (request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val limit = (request.queryParameters["limit"]?.toIntOrNull() ?: defaultLimit).coerceIn(1, 200)
    return ManageListQuery(
        offset = offset,
        limit = limit,
        search = request.queryParameters["query"]?.trim()?.take(200).orEmpty(),
        sortBy = request.queryParameters["sortBy"]?.trim()?.take(50).orEmpty(),
        ascending = request.queryParameters["order"].equals("asc", ignoreCase = true)
    )
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrError(): T? =
    try {
        receive<T>()
    } catch (_: Exception) {
        respond(mapOf("error" to "invalid request body"))
        null
    }

private suspend inline fun <T> ApplicationCall.respondScoped(
    resource: T?,
    botId: String,
    groupId: String,
    getBotId: (T) -> String,
    getGroupId: (T) -> String,
    notFound: String
) {
    if (resource == null || getBotId(resource) != botId || getGroupId(resource) != groupId) {
        respond(mapOf("error" to notFound))
    } else {
        respond(resource)
    }
}

fun Routing.configureBotStatusManager() {
    authenticate("basic") {
        val memoryService by inject<MemoryService>()
        val memoryRepository by inject<MemoryRepository>()
        val memeService by inject<MemeService>()
        val memeRepository by inject<MemeRepository>()
        val evolutionService by inject<EvolutionService>()
        val summaryService by inject<SummaryService>()
        val historyService by inject<HistoryService>()
        val resourceService by inject<ResourceService>()
        val emotionService by inject<EmotionService>()
        val emotionRepository by inject<EmotionRepository>()
        val flowRepository by inject<FlowRepository>()
        val flowGaugeManager by inject<FlowGaugeManager>()
        val volitionRepository by inject<VolitionRepository>()
        val volitionGaugeManager by inject<VolitionGaugeManager>()
        val cronService by inject<CronService>()

        // 获取所有机器人列表
        get("/api/bots") {
            val bots = BotManage.getAllBots().map { roledBot ->
                BotInfo(
                    botId = roledBot.selfId,
                    botName = roledBot.role.name
                )
            }
            call.respond(bots)
        }

        // 获取指定机器人的群组列表（带群名称）
        get("/api/bot/{bot-id}/groups") {
            val botId = call.request.pathVariables["bot-id"]
            if (botId == null) {
                call.respond(mapOf("error" to "bot-id is null"))
            } else {
                val roledBot = BotManage.getBot(botId)
                val refBot = roledBot.refBot
                val groups = ConfigHolder.resolveEnabledGroups(BotManage.getConfigKey(botId), botId)
                    .map { groupId ->
                        val groupName = if (Channel.isPrivate(groupId)) {
                            val userId = Channel.extractUserId(groupId)!!
                            refBot.getStrangerInfo(userId).nickname
                        } else {
                            runCatching {
                                refBot.getGroupList()
                                    .find { it.groupId.toString() == groupId }
                                    ?.groupName
                            }.getOrNull() ?: groupId
                        }
                        GroupInfo(groupId, groupName)
                    }
                call.respond(groups)
            }
        }

        // 获取指定机器人和群组的状态
        get("/api/bot/{bot-id}/group/{group-id}/status") {
            val botId = call.request.pathVariables["bot-id"]
            val groupId = call.request.pathVariables["group-id"]

            if (botId == null || groupId == null) {
                call.respond(mapOf("error" to "bot-id or group-id is null"))
                return@get
            }

            val response = buildGroupStatusResponse(
                botId = botId,
                groupId = groupId,
                emotionService = emotionService,
                flowGaugeManager = flowGaugeManager,
                volitionGaugeManager = volitionGaugeManager,
                evolutionService = evolutionService,
                memoryService = memoryService,
                memeService = memeService,
                historyService = historyService,
            )
            if (response == null) {
                call.respond(mapOf("error" to "group not enabled for this bot"))
                return@get
            }

            call.respond(response)
        }

        get("/api/bot/{bot-id}/group/{group-id}/facts") {
            val listQuery = call.manageListQuery()
            val (items, total) = memoryService.getAllFactsByGroupForManagement(
                call.botId(),
                call.groupId(),
                listQuery
            )
            call.respond(
                PaginatedResponse(
                    items = items.map { it.toRecord() },
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/facts/{fact-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val factId = call.intPathParam("fact-id") ?: return@get call.respond(mapOf("error" to "invalid fact-id"))
            call.respondScoped(
                memoryRepository.getFactById(factId), botId, groupId,
                { it.botId }, { it.groupId }, "fact not found"
            )
        }

        post("/api/bot/{bot-id}/group/{group-id}/facts/vector-search") {
            val request = call.receiveOrError<MemorySearchRequest>() ?: return@post
            call.respond(
                memoryService.searchFactsVector(
                    botId = call.botId(),
                    groupId = call.groupId(),
                    query = request.query,
                    limit = request.limit.coerceIn(1, 100)
                )
            )
        }

        post("/api/bot/{bot-id}/group/{group-id}/facts/graph-search") {
            val request = call.receiveOrError<MemorySearchRequest>() ?: return@post
            call.respond(
                memoryService.searchFactsGraph(
                    botId = call.botId(),
                    groupId = call.groupId(),
                    query = request.query,
                    limit = request.limit.coerceIn(1, 100)
                )
            )
        }

        post("/api/bot/{bot-id}/group/{group-id}/facts") {
            val botId = call.botId()
            val groupId = call.groupId()
            val request = call.receiveOrError<FactRequest>() ?: return@post
            val fact = memoryService.createFact(
                botId = botId, groupId = groupId, keyword = request.keyword,
                description = request.description, entities = request.entities,
                subjects = request.subjects, scopeType = request.scopeType
            )
            if (fact == null) {
                call.respond(mapOf("error" to "fact not found"))
            } else {
                call.respond(fact)
            }
        }

        put("/api/bot/{bot-id}/group/{group-id}/facts/{fact-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val factId = call.intPathParam("fact-id") ?: return@put call.respond(mapOf("error" to "invalid fact-id"))
            val request = call.receiveOrError<FactRequest>() ?: return@put
            call.respondScoped(
                memoryService.updateFact(
                    botId = botId,
                    groupId = groupId,
                    id = factId, keyword = request.keyword,
                    description = request.description, entities = request.entities,
                    subjects = request.subjects, scopeType = request.scopeType
                ), botId, groupId,
                { it.botId }, { it.groupId }, "fact not found"
            )
        }

        delete("/api/bot/{bot-id}/group/{group-id}/facts/{fact-id}") {
            val deleted = memoryService.deleteFact(
                call.botId(),
                call.groupId(),
                call.intPathParam("fact-id") ?: return@delete call.respond(mapOf("error" to "invalid fact-id"))
            )
            call.respond(if (deleted) mapOf("success" to true) else mapOf("error" to "fact not found"))
        }

        get("/api/bot/{bot-id}/group/{group-id}/user-profiles") {
            val listQuery = call.manageListQuery()
            val (items, total) = memoryService.getAllUserProfilesByGroup(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items.map { it.toRecord() },
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/user-profiles/{user-id}") {
            call.respond(memoryRepository.findOrCreateUserProfile(call.botId(), call.groupId(), call.userId()))
        }

        put("/api/bot/{bot-id}/group/{group-id}/user-profiles/{user-id}") {
            val request = call.receiveOrError<UpdateUserProfileRequest>() ?: return@put
            val profile = memoryRepository.updateUserProfile(
                call.botId(),
                call.groupId(),
                call.userId(),
                request.profile,
                request.preferences
            )
            call.respond(profile)
        }

        delete("/api/bot/{bot-id}/group/{group-id}/user-profiles/{user-id}") {
            val deleted = memoryRepository.deleteUserProfile(call.botId(), call.groupId(), call.userId())
            call.respond(if (deleted) mapOf("success" to true) else mapOf("error" to "user-profile not found"))
        }

        get("/api/bot/{bot-id}/group/{group-id}/memes") {
            val listQuery = call.manageListQuery()
            val (items, total) = memeService.getAllMemos(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items,
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/memes/{meme-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val memeId = call.intPathParam("meme-id") ?: return@get call.respond(mapOf("error" to "invalid meme-id"))
            call.respondScoped(
                memeService.getMemoById(memeId), botId, groupId,
                { it.botId }, { it.groupId }, "meme not found"
            )
        }

        put("/api/bot/{bot-id}/group/{group-id}/memes/{meme-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val memeId = call.intPathParam("meme-id") ?: return@put call.respond(mapOf("error" to "invalid meme-id"))
            val request = call.receiveOrError<UpdateMemeRequest>() ?: return@put
            val updated = memeRepository.updateMeme(memeId, request.description, request.purpose, request.tags)
            if (updated != null && updated.botId == botId && updated.groupId == groupId) {
                memeService.upsertToVectorStore(updated)
                call.respond(updated)
            } else {
                call.respond(mapOf("error" to "meme not found"))
            }
        }

        delete("/api/bot/{bot-id}/group/{group-id}/memes/{meme-id}") {
            val deleted = memeService.deleteMemo(
                call.botId(),
                call.groupId(),
                call.intPathParam("meme-id") ?: return@delete call.respond(mapOf("error" to "invalid meme-id"))
            )
            call.respond(if (deleted) mapOf("success" to true) else mapOf("error" to "meme not found"))
        }

        post("/api/bot/{bot-id}/group/{group-id}/memes/vector-search") {
            val request = call.receiveOrError<MemeSearchRequest>() ?: return@post
            val results = memeService.searchByVector(
                botId = call.botId(),
                groupId = call.groupId(),
                query = request.query,
                topK = request.limit.coerceIn(1, 100)
            )
            call.respond(
                MemeVectorSearchResponse(
                    query = request.query,
                    results = results.map { (meme, score) ->
                        MemeVectorSearchResult(
                            meme = meme,
                            score = score,
                            vectorId = meme.vectorId,
                            source = "vector"
                        )
                    }
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/vocabulary") {
            val listQuery = call.manageListQuery()
            val (items, total) = evolutionService.getAllVocabulary(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items.map { it.toRecord() },
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/vocabulary/{vocab-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val vocabId = call.intPathParam("vocab-id") ?: return@get call.respond(mapOf("error" to "invalid vocab-id"))
            call.respondScoped(
                evolutionService.getVocabularyById(vocabId), botId, groupId,
                { it.botId }, { it.groupId }, "vocabulary not found"
            )
        }

        post("/api/bot/{bot-id}/group/{group-id}/vocabulary") {
            val request = call.receiveOrError<VocabularyRequest>() ?: return@post
            val vocab = evolutionService.addOrUpdateWord(
                call.botId(), call.groupId(),
                SlangWord(request.word, request.type, request.meaning, request.example),
                request.weight
            )
            call.respond(vocab)
        }

        put("/api/bot/{bot-id}/group/{group-id}/vocabulary/{vocab-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val vocabId = call.intPathParam("vocab-id") ?: return@put call.respond(mapOf("error" to "invalid vocab-id"))
            val request = call.receiveOrError<VocabularyRequest>() ?: return@put
            call.respondScoped(
                evolutionService.updateWordById(
                    vocabId, request.word, request.type, request.meaning, request.example, request.weight
                ), botId, groupId,
                { it.botId }, { it.groupId }, "vocabulary not found"
            )
        }

        delete("/api/bot/{bot-id}/group/{group-id}/vocabulary/{vocab-id}") {
            val deleted = evolutionService.deleteWordById(
                call.intPathParam("vocab-id") ?: return@delete call.respond(mapOf("error" to "invalid vocab-id")),
                call.botId(), call.groupId()
            )
            call.respond(if (deleted) mapOf("success" to true) else mapOf("error" to "vocabulary not found"))
        }

        get("/api/bot/{bot-id}/group/{group-id}/summaries") {
            val listQuery = call.manageListQuery()
            val (items, total) = summaryService.getAllSummariesByGroup(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items,
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/summaries/{summary-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val summaryId =
                call.intPathParam("summary-id") ?: return@get call.respond(mapOf("error" to "invalid summary-id"))
            call.respondScoped(
                summaryService.getSummaryById(summaryId), botId, groupId,
                { it.botId }, { it.groupId }, "summary not found"
            )
        }

        put("/api/bot/{bot-id}/group/{group-id}/summaries/{summary-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val summaryId =
                call.intPathParam("summary-id") ?: return@put call.respond(mapOf("error" to "invalid summary-id"))
            val request = call.receiveOrError<UpdateSummaryRequest>() ?: return@put
            call.respondScoped(
                summaryService.updateSummary(
                    summaryId, request.timeRange, request.content, request.keyPoints,
                    request.emotionalTone
                ), botId, groupId,
                { it.botId }, { it.groupId }, "summary not found"
            )
        }

        delete("/api/bot/{bot-id}/group/{group-id}/summaries/{summary-id}") {
            val deleted = summaryService.deleteSummary(
                call.intPathParam("summary-id") ?: return@delete call.respond(mapOf("error" to "invalid summary-id"))
            )
            call.respond(if (deleted) mapOf("success" to true) else mapOf("error" to "summary not found"))
        }

        // ── History ──

        get("/api/bot/{bot-id}/group/{group-id}/history") {
            val listQuery = call.manageListQuery()
            val (items, total) = historyService.getAllHistoryByGroup(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items,
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/history/{history-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val historyId =
                call.intPathParam("history-id") ?: return@get call.respond(mapOf("error" to "invalid history-id"))
            call.respondScoped(
                historyService.getHistoryById(historyId), botId, groupId,
                { it.botId }, { it.groupId }, "history not found"
            )
        }

        put("/api/bot/{bot-id}/group/{group-id}/history/{history-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val historyId =
                call.intPathParam("history-id") ?: return@put call.respond(mapOf("error" to "invalid history-id"))
            val request = call.receiveOrError<UpdateHistoryRequest>() ?: return@put
            call.respondScoped(
                historyService.updateHistory(historyId, request.content, request.nick),
                botId, groupId,
                { it.botId }, { it.groupId }, "history not found"
            )
        }

        delete("/api/bot/{bot-id}/group/{group-id}/history/{history-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val historyId =
                call.intPathParam("history-id") ?: return@delete call.respond(mapOf("error" to "invalid history-id"))

            val history = historyService.getHistoryById(historyId)
            if (history == null || history.botId != botId || history.groupId != groupId) {
                call.respond(mapOf("error" to "history not found"))
                return@delete
            }

            val resourceId = history.resource?.id

            val deleted = historyService.deleteHistory(historyId)
            if (!deleted) {
                call.respond(mapOf("error" to "history not found"))
                return@delete
            }

            if (resourceId != null) {
                val memes = memeRepository.findMemesByResourceId(resourceId)
                memes.forEach { meme ->
                    meme.id?.let { memeId ->
                        memeService.deleteMemo(botId, groupId, memeId)
                    }
                }
                resourceService.deleteResource(resourceId)
            }

            call.respond(mapOf("success" to true))
        }

        // ── Resources ──

        get("/api/bot/{bot-id}/group/{group-id}/resources") {
            val listQuery = call.manageListQuery()
            val (items, total) = resourceService.getAllResourcesByGroup(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items,
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        // ── Emotion ──

        get("/api/bot/{bot-id}/group/{group-id}/emotion") {
            val botId = call.botId()
            val groupId = call.groupId()
            val emotion = emotionRepository.getLatestEmotion(botId, groupId)
            if (emotion == null) {
                call.respond(mapOf("error" to "emotion not found"))
            } else {
                call.respond(emotion.toRecord())
            }
        }

        put("/api/bot/{bot-id}/group/{group-id}/emotion") {
            val botId = call.botId()
            val groupId = call.groupId()
            val request = call.receiveOrError<UpdateEmotionRequest>() ?: return@put
            val entity = emotionRepository.getLatestEmotion(botId, groupId)
            if (entity == null) {
                call.respond(mapOf("error" to "emotion not found"))
                return@put
            }
            val tendency = try {
                EmotionalTendencies.valueOf(request.emotionalTendency)
            } catch (_: IllegalArgumentException) {
                call.respond(mapOf("error" to "invalid emotional tendency"))
                return@put
            }
            listOf(
                "stimulus" to request.stimulus,
                "emotion" to request.emotion,
                "mood" to request.mood
            ).forEach { (name, pad) ->
                pad.validate()?.let { error ->
                    call.respond(mapOf("error" to "$name: $error"))
                    return@put
                }
            }
            emotionRepository.updateEmotion(
                entity = entity,
                emotionalTendency = tendency,
                stimulus = request.stimulus,
                emotion = request.emotion,
                mood = request.mood,
                behavior = request.behavior
            )
            val updated = emotionRepository.getLatestEmotion(botId, groupId)
            if (updated == null) {
                call.respond(mapOf("error" to "emotion not found"))
            } else {
                call.respond(updated.toRecord())
            }
        }

        // ── Flow ──

        get("/api/bot/{bot-id}/group/{group-id}/flow") {
            val botId = call.botId()
            val groupId = call.groupId()
            val gauge = flowGaugeManager.get(botId, groupId)
            val entity = flowRepository.getFlowState(botId, groupId)
            if (entity == null) {
                call.respond(mapOf("error" to "flow state not found"))
                return@get
            }
            val record = entity.toRecord()
            val updatedRecord = if (gauge != null) {
                record.copy(flowValue = gauge.state.value)
            } else {
                record
            }
            call.respond(updatedRecord)
        }

        put("/api/bot/{bot-id}/group/{group-id}/flow") {
            val botId = call.botId()
            val groupId = call.groupId()
            val request = call.receiveOrError<UpdateFlowRequest>() ?: return@put
            val coercedFlowValue = request.flowValue.coerceIn(0.0, 100.0)
            val gauge = flowGaugeManager.get(botId, groupId)
            if (gauge != null) {
                gauge.state.value = coercedFlowValue
                gauge.flush()
            }
            flowRepository.updateFlowStateDirect(botId, groupId, coercedFlowValue, request.currentTopic)
            val entity = flowRepository.getFlowState(botId, groupId)
            if (entity == null) {
                call.respond(mapOf("error" to "flow state not found"))
            } else {
                call.respond(entity.toRecord())
            }
        }

        // ── Volition ──

        get("/api/bot/{bot-id}/group/{group-id}/volition") {
            val botId = call.botId()
            val groupId = call.groupId()
            val gauge = volitionGaugeManager.get(botId, groupId)
            val entity = volitionRepository.getVolitionState(botId, groupId)
            if (entity == null) {
                call.respond(mapOf("error" to "volition state not found"))
                return@get
            }
            val record = entity.toRecord()
            val updatedRecord = if (gauge != null) {
                record.copy(fatigue = gauge.state.fatigue, stimulus = gauge.state.stimulus)
            } else {
                record
            }
            call.respond(updatedRecord)
        }

        put("/api/bot/{bot-id}/group/{group-id}/volition") {
            val botId = call.botId()
            val groupId = call.groupId()
            val request = call.receiveOrError<UpdateVolitionRequest>() ?: return@put
            val coercedFatigue = request.fatigue.coerceIn(0.0, 100.0)
            val coercedStimulus = request.stimulus.coerceIn(0.0, 100.0)
            val gauge = volitionGaugeManager.get(botId, groupId)
            if (gauge != null) {
                gauge.state.fatigue = coercedFatigue
                gauge.state.stimulus = coercedStimulus
                gauge.flush()
            }
            volitionRepository.updateVolitionStateDirect(botId, groupId, coercedFatigue, coercedStimulus)
            val entity = volitionRepository.getVolitionState(botId, groupId)
            if (entity == null) {
                call.respond(mapOf("error" to "volition state not found"))
            } else {
                call.respond(entity.toRecord())
            }
        }

        // ── Cron Tasks ──

        get("/api/bot/{bot-id}/group/{group-id}/cron-tasks") {
            val listQuery = call.manageListQuery()
            val (items, total) = cronService.store.getAllTasks(call.botId(), call.groupId(), listQuery)
            call.respond(
                PaginatedResponse(
                    items = items,
                    total = total,
                    offset = listQuery.offset,
                    limit = listQuery.limit
                )
            )
        }

        get("/api/bot/{bot-id}/group/{group-id}/cron-tasks/{task-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val taskId = call.parameters["task-id"]
            if (taskId == null) {
                call.respond(mapOf("error" to "invalid task-id"))
                return@get
            }
            val task = cronService.store.getTask(botId, groupId, taskId)
            if (task == null) {
                call.respond(mapOf("error" to "cron task not found"))
            } else {
                call.respond(task)
            }
        }

        put("/api/bot/{bot-id}/group/{group-id}/cron-tasks/{task-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val taskId = call.parameters["task-id"]
            if (taskId == null) {
                call.respond(mapOf("error" to "invalid task-id"))
                return@put
            }
            val request = call.receiveOrError<UpdateCronTaskRequest>() ?: return@put
            val existing = cronService.store.getTask(botId, groupId, taskId)
            if (existing == null || existing.botId != botId || existing.groupId != groupId) {
                call.respond(mapOf("error" to "cron task not found"))
                return@put
            }
            val cronExpr = request.cronExpression?.trim()?.takeIf { it.isNotEmpty() }
            val status = when (request.status?.lowercase()) {
                "active" -> CronTaskStatus.ACTIVE
                "deleted" -> CronTaskStatus.DELETED
                "fired" -> CronTaskStatus.FIRED
                null -> existing.status
                else -> {
                    call.respond(mapOf("error" to "invalid status, valid: active/fired/deleted"))
                    return@put
                }
            }
            val updated = existing.copy(
                content = request.content ?: existing.content,
                triggerTime = request.triggerTime ?: existing.triggerTime,
                targetUserId = request.targetUserId ?: existing.targetUserId,
                cronExpression = if (request.cronExpression != null) cronExpr else existing.cronExpression,
                status = status
            )
            cronService.wheel.removeTask(existing)
            cronService.wheel.pushTask(updated)
            call.respond(updated)
        }

        delete("/api/bot/{bot-id}/group/{group-id}/cron-tasks/{task-id}") {
            val botId = call.botId()
            val groupId = call.groupId()
            val taskId = call.parameters["task-id"]
            if (taskId == null) {
                call.respond(mapOf("error" to "invalid task-id"))
                return@delete
            }
            val existing = cronService.store.getTask(botId, groupId, taskId)
            if (existing == null || existing.botId != botId || existing.groupId != groupId) {
                call.respond(mapOf("error" to "cron task not found"))
                return@delete
            }
            cronService.wheel.removeTask(existing)
            call.respond(mapOf("success" to true))
        }
    }
}
