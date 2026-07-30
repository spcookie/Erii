package uesugi.routing

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import uesugi.common.toolkit.JSON
import uesugi.core.component.usage.*
import uesugi.core.message.history.HourlyMessageCount
import uesugi.core.state.flow.FlowMeterState
import kotlin.test.Test
import kotlin.test.assertEquals

class BotInfoRoutesTest {

    @Test
    fun `bot info endpoint requires basic authentication`() = testApplication {
        application {
            install(Authentication) {
                basic("basic") {
                    validate { credentials -> UserIdPrincipal(credentials.name) }
                }
            }
            routing {
                configureBotInfoRoutes()
            }
        }

        val response = client.get("/api/bot/test-bot/group/test-group/info")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bot info response keeps every status and usage field`() {
        val response = BotInfoResponse(
            botId = "bot",
            groupId = "group",
            timestamp = "2026-07-28T09:30:00Z",
            status = GroupStatusResponse(
                botId = "bot",
                botName = "Bot",
                groupId = "group",
                groupName = "Group",
                behaviorProfile = null,
                pad = null,
                flowState = BotStatus.FlowState(42.0, FlowMeterState.GETTING_BETTER),
                volitionState = BotStatus.VolitionState(
                    impulse = 1.0,
                    stimulus = 2.0,
                    fatigue = 3.0,
                    shouldSpeak = true,
                ),
                vocabularies = listOf("word"),
                summary = "summary",
                factSize = 1,
                userProfileSize = 1,
                facts = BotStatus.Facts(emptyList(), emptyList()),
                userProfiles = emptyList(),
                memeSize = 1,
                analyzedMemeSize = 1,
                memes = emptyList(),
                pluginStats = BotStatus.PluginStats(0, 0, 0, 0, emptyList()),
                hourlyMsgCounts = listOf(HourlyMessageCount("12:00", 1, 2)),
            ),
            usage = usageSummary(),
        )

        val json = JSON.encodeToJsonElement(response).jsonObject

        assertEquals(
            setOf("botId", "groupId", "timestamp", "status", "usage"),
            json.keys,
        )
        assertEquals(
            setOf(
                "botId", "botName", "groupId", "groupName", "behaviorProfile", "pad",
                "flowState", "volitionState", "vocabularies", "summary", "factSize",
                "userProfileSize", "facts", "userProfiles", "memeSize", "analyzedMemeSize",
                "memes", "pluginStats", "hourlyMsgCounts",
            ),
            json.getValue("status").jsonObject.keys,
        )
        assertEquals(
            setOf(
                "todayCacheHitInput", "todayCacheMissInput", "todayOutput", "todayCost",
                "priceUnit", "pricing", "totalCacheHitInput", "totalCacheMissInput",
                "totalOutput", "totalCost", "todayCacheHitRate", "sceneBars", "modelBars",
                "dailySeries", "dailyViews",
            ),
            json.getValue("usage").jsonObject.keys,
        )
    }

    private fun usageSummary(): TokenUsageSummary {
        val bar = TokenUsageChartPoint("聊天", 1, 2, 3)
        val pricing = TokenUsageTierPricing(0.1, 0.2, 0.3)
        return TokenUsageSummary(
            todayCacheHitInput = 1,
            todayCacheMissInput = 2,
            todayOutput = 3,
            todayCost = 0.1,
            priceUnit = "USD",
            pricing = TokenUsagePricing(pricing, pricing, pricing),
            totalCacheHitInput = 4,
            totalCacheMissInput = 5,
            totalOutput = 6,
            totalCost = 0.2,
            todayCacheHitRate = 33.33,
            sceneBars = listOf(bar),
            modelBars = listOf(bar),
            dailySeries = listOf(DailyTokenUsagePoint("2026-07-28", 6, 0.1)),
            dailyViews = listOf(
                DailyTokenUsageSummary(
                    date = "2026-07-28",
                    cacheHitInput = 1,
                    cacheMissInput = 2,
                    output = 3,
                    cost = 0.1,
                    cacheHitRate = 33.33,
                    sceneBars = listOf(bar),
                    modelBars = listOf(bar),
                )
            ),
        )
    }
}
