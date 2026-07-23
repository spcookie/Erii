package uesugi.routing

import io.ktor.server.auth.*
import io.ktor.server.jte.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import uesugi.common.BotManage
import uesugi.common.data.EmotionalTendencies
import uesugi.common.data.PAD
import uesugi.common.toolkit.ConfigHolder
import uesugi.core.message.history.HistoryService
import uesugi.core.message.history.HourlyMessageCount
import uesugi.core.state.emotion.BehaviorProfile
import uesugi.core.state.emotion.EmotionService
import uesugi.core.state.evolution.EvolutionService
import uesugi.core.state.flow.FlowGaugeManager
import uesugi.core.state.flow.FlowMeterState
import uesugi.core.state.meme.MemeService
import uesugi.core.state.memory.MemoryService
import uesugi.core.state.memory.Scopes
import uesugi.core.state.volition.VolitionGaugeManager
import uesugi.onebot.sdk.client.api.getGroupList
import uesugi.plugin.ExtensionRegister
import uesugi.spi.CmdExtension
import uesugi.spi.PassiveExtension
import uesugi.spi.RouteExtension


/**
 * 构建 PluginStats
 */
private fun buildPluginStats(botId: String? = null): BotStatus.PluginStats {
    if (botId == null) {
        val allExtensions = ExtensionRegister.getAllExtensions()

        return BotStatus.PluginStats(
            totalExtensions = allExtensions.size,
            cmdExtensions = ExtensionRegister.getCmdExtensions().size,
            routeExtensions = ExtensionRegister.getRouteExtensions().size,
            passiveExtensions = ExtensionRegister.getPassiveExtensions().size,
            plugins = ExtensionRegister.getAllPlugins().map { (pluginId, extensions) ->
                BotStatus.PluginInfo(
                    id = pluginId,
                    extensionCount = extensions.size
                )
            }
        )
    } else {
        var cmdExtensions = 0
        var routeExtensions = 0
        var passiveExtensions = 0

        val plugins = ExtensionRegister.getAllPlugins()
            .filter { ConfigHolder.isPluginEnabled(BotManage.getConfigKey(botId), it.key) }
            .map { (pluginId, extensions) ->
                cmdExtensions += extensions.count { it is CmdExtension<*, *, *> }
                routeExtensions += extensions.count { it is RouteExtension<*> }
                passiveExtensions += extensions.count { it is PassiveExtension<*> }
                BotStatus.PluginInfo(
                    id = pluginId,
                    extensionCount = extensions.size
                )
            }

        return BotStatus.PluginStats(
            totalExtensions = cmdExtensions + routeExtensions + passiveExtensions,
            cmdExtensions = cmdExtensions,
            routeExtensions = routeExtensions,
            passiveExtensions = passiveExtensions,
            plugins = plugins
        )
    }

}

/**
 * 构建单个群组的 ByGroup 状态
 */
private fun buildGroupStatus(
    botId: String,
    groupId: String,
    emoticon: EmotionalTendencies,
    emotionService: EmotionService,
    flowGaugeManager: FlowGaugeManager,
    volitionGaugeManager: VolitionGaugeManager,
    evolutionService: EvolutionService,
    memoryService: MemoryService,
    memoService: MemeService
): BotStatus.ByGroup {
    val behaviorProfile = emotionService.getCurrentBehaviorProfile(botId, groupId)
    val pad = emotionService.getCurrentEmotion(botId, groupId)
    val flowState = flowGaugeManager.getOrCreate(botId, groupId, emoticon)
        .let { it.state.value to it.mapToState() }
    val volitionState = volitionGaugeManager.getOrCreate(botId, groupId, emoticon)
        .let {
            BotStatus.VolitionState(
                impulse = it.calculateRawImpulse(),
                stimulus = it.state.stimulus,
                fatigue = it.state.fatigue,
                shouldSpeak = it.shouldSpeak()
            )
        }
    val vocabularies = evolutionService.getActiveVocabulary(botId, groupId, 999).map { it.word }
    val summary = memoryService.getSummary(botId, groupId)?.content
    val factSize = memoryService.getFactSize(botId, groupId)
    val userProfileSize = memoryService.getUserProfileSize(botId, groupId)
    val allFactsByGroup = memoryService.getAllFactsByGroup(botId, groupId).first
    val allUserProfilesByGroup = memoryService.getAllUserProfilesByGroup(botId, groupId).first

    val scopeByFacts = allFactsByGroup.groupBy(
        { it.scopeType },
        {
            BotStatus.Fact(
                it.keyword,
                it.description,
                it.entities,
                it.subjects.split(",")
            )
        }
    )

    val facts = BotStatus.Facts(
        group = scopeByFacts[Scopes.GROUP] ?: emptyList(),
        user = scopeByFacts[Scopes.USER] ?: emptyList()
    )

    val userProfiles = allUserProfilesByGroup.map {
        BotStatus.UserProfile(
            id = it.userId,
            profile = it.profile,
            preferences = it.preferences
        )
    }

    val allMemes = memoService.getAllMemos(botId, groupId).first
    val analyzedMemes = allMemes.filter { it.description != null }
    val memeSize = allMemes.size.toLong()
    val analyzedMemeSize = analyzedMemes.size.toLong()
    val memes = analyzedMemes.sortedByDescending { it.usageCount }.map {
        BotStatus.Meme(
            id = it.id!!,
            description = it.description,
            purpose = it.purpose,
            tags = it.tags?.split(",")?.map { t -> t.trim() } ?: emptyList(),
            seenCount = it.seenCount,
            usageCount = it.usageCount
        )
    }

    return BotStatus.ByGroup(
        groupId = groupId,
        behaviorProfile = behaviorProfile,
        pad = pad,
        flowState = BotStatus.FlowState.fromPair(flowState),
        volitionState = volitionState,
        vocabularies = vocabularies,
        summary = summary,
        factSize = factSize,
        userProfileSize = userProfileSize,
        facts = facts,
        userProfiles = userProfiles,
        memeSize = memeSize,
        analyzedMemeSize = analyzedMemeSize,
        memes = memes
    )
}

fun Routing.configureBotStatus() {
    authenticate("basic") {
        val emotionService by inject<EmotionService>()
        val flowGaugeManager by inject<FlowGaugeManager>()
        val volitionGaugeManager by inject<VolitionGaugeManager>()
        val evolutionService by inject<EvolutionService>()
        val memoryService by inject<MemoryService>()
        val memoService by inject<MemeService>()
        val historyService by inject<HistoryService>()

        get("/bots") {
            call.respond(BotManage.getAllBotIds())
        }
        get("/bot/{bot-id}/groups") {
            val botId = call.request.pathVariables["bot-id"]
            if (botId == null) {
                call.respond(mapOf("error" to "bot-id is null"))
            } else {
                val groups = ConfigHolder.getEffectiveEnableGroups(BotManage.getConfigKey(botId))
                call.respond(groups)
            }
        }
        get("/status/{id}") {
            val id = call.request.pathVariables["id"]
            if (id == null) {
                call.respond(mapOf("error" to "id is null"))
            } else {
                val roledBot = BotManage.getBot(id)
                val refBot = roledBot.refBot
                val groupList = refBot.getGroupList()
                val groups = groupList.map { it.groupId.toString() }
                    .filter { ConfigHolder.getEffectiveEnableGroups(BotManage.getConfigKey(id)).contains(it) }.toList()

                val pluginStats = buildPluginStats()
                val emoticon = roledBot.role.emoticon
                val botStatusByGroups = groups.map { gId ->
                    buildGroupStatus(
                        botId = id,
                        groupId = gId,
                        emoticon = emoticon,
                        emotionService = emotionService,
                        flowGaugeManager = flowGaugeManager,
                        volitionGaugeManager = volitionGaugeManager,
                        evolutionService = evolutionService,
                        memoryService = memoryService,
                        memoService = memoService
                    )
                }

                val botName = roledBot.role.name
                call.respond(BotStatus(id, botName, groups, botStatusByGroups, pluginStats))
            }
        }

        // TUI JSON API: 获取所有机器人列表
        get("/api/bots") {
            val bots = BotManage.getAllBots().map { roledBot ->
                BotInfo(
                    botId = roledBot.selfId,
                    botName = roledBot.role.name
                )
            }
            call.respond(bots)
        }

        // TUI JSON API: 获取指定机器人的群组列表（带群名称）
        get("/api/bot/{bot-id}/groups") {
            val botId = call.request.pathVariables["bot-id"]
            if (botId == null) {
                call.respond(mapOf("error" to "bot-id is null"))
            } else {
                val roledBot = BotManage.getBot(botId)
                val refBot = roledBot.refBot
                val enabledGroups = ConfigHolder.getEffectiveEnableGroups(BotManage.getConfigKey(botId))
                val groupList = refBot.getGroupList()
                val groups = groupList
                    .filter { enabledGroups.contains(it.groupId.toString()) }
                    .map { GroupInfo(groupId = it.groupId.toString(), groupName = it.groupName) }
                call.respond(groups)
            }
        }

        // TUI JSON API: 获取指定机器人和群组的状态
        get("/api/bot/{bot-id}/group/{group-id}/status") {
            val botId = call.request.pathVariables["bot-id"]
            val groupId = call.request.pathVariables["group-id"]

            if (botId == null || groupId == null) {
                call.respond(mapOf("error" to "bot-id or group-id is null"))
                return@get
            }

            val roledBot = BotManage.getBot(botId)
            val refBot = roledBot.refBot
            val groupList = refBot.getGroupList()
            val enabledGroups = ConfigHolder.getEffectiveEnableGroups(BotManage.getConfigKey(botId))

            if (!enabledGroups.contains(groupId)) {
                call.respond(mapOf("error" to "group not enabled for this bot"))
                return@get
            }

            val groupName = groupList.find { it.groupId.toString() == groupId }?.groupName ?: groupId
            val pluginStats = buildPluginStats(botId)
            val groupStatus = buildGroupStatus(
                botId = botId,
                groupId = groupId,
                emoticon = roledBot.role.emoticon,
                emotionService = emotionService,
                flowGaugeManager = flowGaugeManager,
                volitionGaugeManager = volitionGaugeManager,
                evolutionService = evolutionService,
                memoryService = memoryService,
                memoService = memoService
            )
            val hourlyMsgCounts = historyService.getHourlyMessageCounts(botId, groupId, 12)

            val response = GroupStatusResponse(
                botId = botId,
                botName = roledBot.role.name,
                groupId = groupId,
                groupName = groupName,
                behaviorProfile = groupStatus.behaviorProfile,
                pad = groupStatus.pad,
                flowState = groupStatus.flowState,
                volitionState = groupStatus.volitionState,
                vocabularies = groupStatus.vocabularies,
                summary = groupStatus.summary,
                factSize = groupStatus.factSize,
                userProfileSize = groupStatus.userProfileSize,
                facts = groupStatus.facts,
                userProfiles = groupStatus.userProfiles,
                memeSize = groupStatus.memeSize,
                analyzedMemeSize = groupStatus.analyzedMemeSize,
                memes = groupStatus.memes,
                pluginStats = pluginStats,
                hourlyMsgCounts = hourlyMsgCounts
            )

            call.respond(response)
        }

        // 单群组状态页面
        get("/view/{botId}/{groupId}") {
            val botId = call.parameters["botId"]
            val groupId = call.parameters["groupId"]

            if (botId == null || groupId == null) {
                call.respondRedirect("/")
                return@get
            }

            val roledBot = BotManage.getBot(botId)
            val refBot = roledBot.refBot
            val groupList = refBot.getGroupList()
            val groups = groupList.map { it.groupId.toString() }
                .filter { ConfigHolder.getEffectiveEnableGroups(BotManage.getConfigKey(botId)).contains(it) }.toList()

            if (!groups.contains(groupId)) {
                call.respondRedirect("/")
                return@get
            }

            val groupName = groupList.find { it.groupId.toString() == groupId }?.groupName ?: groupId

            val pluginStats = buildPluginStats(botId)
            val groupStatus = buildGroupStatus(
                botId = botId,
                groupId = groupId,
                emoticon = roledBot.role.emoticon,
                emotionService = emotionService,
                flowGaugeManager = flowGaugeManager,
                volitionGaugeManager = volitionGaugeManager,
                evolutionService = evolutionService,
                memoryService = memoryService,
                memoService = memoService
            )

            val hourlyMsgCounts = historyService.getHourlyMessageCounts(botId, groupId, 12)

            val viewModel = GroupStatusViewModel(
                botId = botId,
                botName = roledBot.role.name,
                groupId = groupId,
                groupName = groupName,
                groupStatus = groupStatus,
                pluginStats = pluginStats,
                currentTime = formatCurrentTime(),
                basePath = "",
                hourlyMsgCounts = hourlyMsgCounts
            )

            call.respond(JteContent("group-status.kte", mapOf("vm" to viewModel)))
        }
    }
}

@Serializable
data class BotInfo(
    val botId: String,
    val botName: String
)

@Serializable
data class GroupInfo(
    val groupId: String,
    val groupName: String
)

@Serializable
data class GroupStatusResponse(
    val botId: String,
    val botName: String,
    val groupId: String,
    val groupName: String,
    val behaviorProfile: BehaviorProfile?,
    val pad: PAD?,
    val flowState: BotStatus.FlowState,
    val volitionState: BotStatus.VolitionState,
    val vocabularies: List<String>,
    val summary: String?,
    val factSize: Long,
    val userProfileSize: Long,
    val facts: BotStatus.Facts,
    val userProfiles: List<BotStatus.UserProfile>,
    val memeSize: Long,
    val analyzedMemeSize: Long,
    val memes: List<BotStatus.Meme>,
    val pluginStats: BotStatus.PluginStats,
    val hourlyMsgCounts: List<HourlyMessageCount>
)

@Serializable
data class BotStatus(
    val id: String,
    val name: String,
    val groups: List<String>,
    val status: List<ByGroup>,
    val pluginStats: PluginStats
) {
    @Serializable
    data class PluginStats(
        val totalExtensions: Int,
        val cmdExtensions: Int,
        val routeExtensions: Int,
        val passiveExtensions: Int,
        val plugins: List<PluginInfo>
    )

    @Serializable
    data class PluginInfo(
        val id: String,
        val extensionCount: Int
    )

    @Serializable
    data class ByGroup(
        val groupId: String,
        val behaviorProfile: BehaviorProfile?,
        val pad: PAD?,
        val flowState: FlowState,
        val volitionState: VolitionState,
        val vocabularies: List<String>,
        val summary: String?,
        val factSize: Long,
        val userProfileSize: Long,
        val facts: Facts,
        val userProfiles: List<UserProfile>,
        val memeSize: Long,
        val analyzedMemeSize: Long,
        val memes: List<Meme>
    )

    @Serializable
    data class Meme(
        val id: Int,
        val description: String?,
        val purpose: String?,
        val tags: List<String>,
        val seenCount: Int,
        val usageCount: Int
    )

    @Serializable
    data class Facts(
        val group: List<Fact>,
        val user: List<Fact>
    )

    @Serializable
    data class Fact(
        val keyword: String,
        val description: String,
        val entities: List<String>,
        val subjects: List<String>
    )

    @Serializable
    data class UserProfile(
        var id: String,
        var profile: String,
        var preferences: String
    )

    @Serializable
    data class VolitionState(
        val impulse: Double,
        val stimulus: Double,
        val fatigue: Double,
        val shouldSpeak: Boolean
    )

    @Serializable
    data class FlowState(val meter: Double, val state: FlowMeterState) {
        companion object {
            fun fromPair(pair: Pair<Double, FlowMeterState>): FlowState {
                return FlowState(pair.first, pair.second)
            }
        }
    }
}
