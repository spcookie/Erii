package uesugi.core.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import uesugi.common.BotManage
import uesugi.common.ChatToolSet
import uesugi.common.event.ProactiveSpeakEvent
import uesugi.common.toolkit.ref
import uesugi.core.component.search.WebSearchTool
import uesugi.core.component.stt.ChatAudioTool
import uesugi.core.component.vision.ChatVisionTool
import uesugi.core.cron.CronService

data class ToolEnv(
    val chatToolSet: ChatToolSet,
    val webSearch: Boolean,
    val chatVision: Boolean,
    val multimodal: Boolean = false,
    val nativeAudio: Boolean = false,
    val toolSetBuilder: ((ChatToolSet) -> List<ToolSet>)?,
    val ruleToolSet: RuleToolSet?,
    val cronToolSet: CronToolSet?
)

context(env: ToolEnv)
fun baseTools() = buildList {
    addAll(env.chatToolSet.asTools())
    addAll(SilentToolSet.asTools())
    addAll(MetaToolSet.asTools())
}

context(env: ToolEnv)
fun webTools() =
    if (env.webSearch) WebSearchTool.asTools() else emptyList()

context(env: ToolEnv)
fun chatVision() =
    if (env.chatVision) ChatVisionTool(env.multimodal).asTools() else emptyList()

context(env: ToolEnv)
fun chatAudio() = ChatAudioTool(env.nativeAudio).asTools()


context(env: ToolEnv)
fun extraTools() =
    env.toolSetBuilder?.invoke(env.chatToolSet)
        ?.flatMap { it.asTools() }
        ?: emptyList()

context(env: ToolEnv)
fun ruleTools() =
    env.ruleToolSet?.asTools() ?: emptyList()

context(env: ToolEnv)
fun cronTools() =
    env.cronToolSet?.asTools() ?: emptyList()

context(env: ToolEnv)
fun buildToolRegistry(): ToolRegistry =
    ToolRegistry {
        tools(baseTools())
        tools(ruleTools())
        tools(cronTools())
        tools(webTools())
        tools(chatVision())
        tools(chatAudio())
        tools(extraTools())
    }

fun buildChatToolSet(event: ProactiveSpeakEvent, context: Context): ChatToolSet {
    val currentBot = BotManage.getBot(event.botId)
    val channelId = event.groupId
    val client = currentBot.refBot

    return AgentChatToolSet(
        client = client,
        channelId = channelId,
        context = context
    )
}

fun buildRuleToolSet(event: ProactiveSpeakEvent, context: Context): RuleToolSet? {
    return event.senderId?.let { senderId ->
        RuleToolSet(
            botId = event.botId,
            groupId = event.groupId,
            userId = senderId,
            admins = context.admins()
        )
    }
}

fun buildCronToolSet(event: ProactiveSpeakEvent): CronToolSet {
    val cronService: CronService by ref()
    return CronToolSet(
        botId = event.botId,
        groupId = event.groupId,
        senderId = event.senderId,
        store = cronService.store,
        wheel = cronService.wheel
    )
}

fun buildToolEnv(
    event: ProactiveSpeakEvent,
    context: Context,
    multimodal: Boolean = false,
    nativeAudio: Boolean = false,
): ToolEnv {
    return ToolEnv(
        buildChatToolSet(event, context),
        event.chatVision,
        event.webSearch,
        multimodal,
        nativeAudio,
        event.toolSetBuilder,
        buildRuleToolSet(event, context),
        buildCronToolSet(event)
    )
}
