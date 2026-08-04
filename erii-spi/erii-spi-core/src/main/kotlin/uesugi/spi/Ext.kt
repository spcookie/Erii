package uesugi.spi

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.data.Channel
import uesugi.common.data.HistoryRecord
import uesugi.common.data.HistoryTable
import uesugi.common.event.route.CallRouteEvent
import uesugi.common.route.CmdRouteRule
import uesugi.common.route.LLMRouteRule
import uesugi.common.route.RouteRule
import uesugi.common.toolkit.ConfigHolder
import uesugi.onebot.core.model.MessageContent
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.client.api.sendPrivateMsg
import kotlin.time.Clock
import kotlin.time.Duration

fun Meta.getRefBot() = roledBot.refBot

suspend fun Meta.sendMessage(content: MessageContent): Int {
    val bot = getRefBot()
    return if (Channel.isPrivate(groupId)) {
        val userId = Channel.extractUserId(groupId)
            ?: throw IllegalArgumentException("Invalid private channel groupId: $groupId")
        bot.sendPrivateMsg(userId, content)
    } else {
        bot.sendGroupMsg(groupId.toLong(), content)
    }
}

fun Meta.getAdmins() = ConfigHolder.getAdmins(BotManage.getConfigKey(botId), groupId)

fun Meta.isAdmin() = senderId in getAdmins()

fun Meta.callLLMRoute(
    target: String,
    botId: String? = null,
    groupId: String? = null,
    senderId: String? = null,
    input: String? = null
) = callRoute(
    this,
    LLMRouteRule(target, "__ignore__"),
    botId,
    groupId,
    senderId,
    input
)

fun Meta.callCmdRoute(
    target: String,
    botId: String? = null,
    groupId: String? = null,
    senderId: String? = null,
    input: String? = null
) = callRoute(
    this,
    CmdRouteRule(target),
    botId,
    groupId,
    senderId,
    input
)

private fun callRoute(
    meta: Meta,
    target: RouteRule,
    botId: String? = null,
    groupId: String? = null,
    senderId: String? = null,
    input: String? = null
) = EventBus.postAsync(
    CallRouteEvent(
        botId = botId ?: meta.botId,
        groupId = groupId ?: meta.groupId,
        senderId = senderId ?: meta.senderId ?: "",
        input = input ?: meta.input ?: "",
        hit = target
    )
)

suspend fun Database.getLatestHistory(
    botId: String,
    groupId: String,
    limit: Int,
    range: Duration
): List<HistoryRecord> {
    val now = Clock.System.now()
    val oneDayAgo = now - range
    val timeZone = TimeZone.currentSystemDefault()
    return getHistory {
        HistoryTable.selectAll()
            .where {
                (HistoryTable.botId eq botId) and
                        (HistoryTable.groupId eq groupId) and
                        (HistoryTable.createdAt greaterEq oneDayAgo.toLocalDateTime(timeZone)) and
                        (HistoryTable.createdAt lessEq now.toLocalDateTime(timeZone))
            }
            .orderBy(HistoryTable.createdAt to SortOrder.DESC)
            .limit(limit)
    }.reversed()
}