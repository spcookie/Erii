package uesugi.routing

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import uesugi.core.component.usage.TokenUsageRepository
import uesugi.core.component.usage.TokenUsageSummary
import uesugi.core.message.history.HistoryService
import uesugi.core.state.emotion.EmotionService
import uesugi.core.state.evolution.EvolutionService
import uesugi.core.state.flow.FlowGaugeManager
import uesugi.core.state.meme.MemeService
import uesugi.core.state.memory.MemoryService
import uesugi.core.state.volition.VolitionGaugeManager
import kotlin.time.Clock

fun Routing.configureBotInfoRoutes() {
    authenticate("basic") {
        val emotionService by inject<EmotionService>()
        val flowGaugeManager by inject<FlowGaugeManager>()
        val volitionGaugeManager by inject<VolitionGaugeManager>()
        val evolutionService by inject<EvolutionService>()
        val memoryService by inject<MemoryService>()
        val memeService by inject<MemeService>()
        val historyService by inject<HistoryService>()
        val tokenUsageRepository by inject<TokenUsageRepository>()

        get("/api/bot/{bot-id}/group/{group-id}/info") {
            val botId = call.parameters["bot-id"]
            val groupId = call.parameters["group-id"]
            if (botId == null || groupId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "bot-id or group-id is null"),
                )
                return@get
            }

            val status = buildGroupStatusResponse(
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
            if (status == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "bot or group not found or group not enabled"),
                )
                return@get
            }

            call.respond(
                BotInfoResponse(
                    botId = botId,
                    groupId = groupId,
                    timestamp = Clock.System.now().toString(),
                    status = status,
                    usage = tokenUsageRepository.summary(botId = botId, groupId = groupId),
                )
            )
        }
    }
}

@Serializable
data class BotInfoResponse(
    val botId: String,
    val groupId: String,
    val timestamp: String,
    val status: GroupStatusResponse,
    val usage: TokenUsageSummary,
)
