package uesugi.core.message.pipeline

import kotlinx.coroutines.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.BotManage
import uesugi.common.EventBus
import uesugi.common.data.*
import uesugi.common.message.CommandUtil
import uesugi.common.message.MessageContext
import uesugi.common.toolkit.logger
import uesugi.core.component.storage.ObjectStorage
import uesugi.core.component.usage.UsageContext
import uesugi.core.message.history.HistorySavedEvent
import uesugi.core.message.history.HistoryService
import uesugi.core.message.resource.ResourceService
import uesugi.core.route.CmdRuleRegister
import uesugi.core.route.RouteCalledEvent
import uesugi.core.route.RoutingAgent
import java.io.File
import java.net.URL
import java.util.*
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MessagePipeline(
    private val historyService: HistoryService,
    private val resourceService: ResourceService,
    private val storage: ObjectStorage,
) {
    private val log = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun process(context: MessageContext, roleName: String) {
        scope.launch {
            val record = saveHistory(context)
            if (context.senderId != context.botId) {
                EventBus.postAsync(HistorySavedEvent(context.parsedMessage.isAtBot, record))
                routeCall(context, roleName)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun saveHistory(context: MessageContext): HistoryRecord {
        val parsed = context.parsedMessage
        return withContext(Dispatchers.IO) {
            var resource: ResourceRecord? = null

            if (parsed.imageUrl != null) {
                val imageUrl = parsed.imageUrl!!
                val format = parsed.imageFormat

                val buffer = when {
                    imageUrl.startsWith("base64://") -> {
                        val data = Base64.getDecoder().decode(imageUrl.removePrefix("base64://"))
                        Buffer().write(data).readByteString()
                    }

                    imageUrl.startsWith("file://") -> {
                        val data = File(imageUrl.removePrefix("file://")).readBytes()
                        Buffer().write(data).readByteString()
                    }

                    else -> {
                        URL(imageUrl).openStream().use { input ->
                            input.source().buffer().readByteString()
                        }
                    }
                }
                val size = buffer.size.toLong()
                val md5 = buffer.md5().hex()

                val resourceRecord = transaction {
                    ResourceEntity.find { ResourceTable.md5 eq md5 }.firstOrNull()?.toRecord()
                }

                val path = if (resourceRecord != null) {
                    resourceRecord.url
                } else {
                    val newPath = "./image/${context.groupId}/${Uuid.random().toHexString()}.${format}"
                    storage.put(
                        newPath.toPath(),
                        Buffer().write(buffer).inputStream().source()
                    )
                    newPath
                }

                resource = resourceService.saveResource(
                    ResourceRecord(
                        botMark = context.botId,
                        groupId = context.groupId,
                        url = path,
                        fileName = path.substringAfterLast("/"),
                        size = size,
                        md5 = md5,
                        createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                    )
                )
            }

            historyService.saveHistory(
                HistoryRecord(
                    botMark = context.botId,
                    groupId = context.groupId,
                    userId = context.senderId,
                    nick = context.senderNick,
                    messageType = parsed.messageType,
                    content = parsed.content,
                    resource = resource,
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                )
            )
        }
    }

    private fun routeCall(context: MessageContext, roleName: String) {
        val parsed = context.parsedMessage

        if (parsed.isAtBot) {
            if (CommandUtil.isAtCommand(parsed.content)) {
                dispatchCommand(
                    context.copy(parsedMessage = parsed.copy(content = CommandUtil.removeAtPrefix(parsed.content))),
                    CommandUtil.parseAtCommand(parsed.content)!!
                )
            } else {
                dispatchRoute(context, roleName)
            }
        } else if (CommandUtil.isCommand(parsed.content)) {
            dispatchCommand(context, CommandUtil.parseCommand(parsed.content)!!)
        }
    }

    private fun dispatchRoute(context: MessageContext, roleName: String) {
        scope.launch {
            UsageContext.withUsage(context.botId, context.groupId) {
                val content = context.parsedMessage.content
                log.info("Robot [$roleName(${context.botId})] is @, triggering active speech")
                val route = RoutingAgent.route(context.botId, context.groupId, content)
                log.info("Routing results: {}", route.name)
                EventBus.postAsync(
                    RouteCalledEvent(
                        botId = context.botId,
                        groupId = context.groupId,
                        senderId = context.senderId,
                        input = "你被群友 ${context.senderNick}(${context.senderId}) @了，内容：${content}",
                        hit = route
                    )
                )
            }
        }
    }

    private fun dispatchCommand(context: MessageContext, command: String) {
        log.info("Robot(${BotManage.getConfigKey(context.botId)}) receives the command $command")
        val cmd = CmdRuleRegister.getRuleForBot(command, context.botId)
        if (cmd == null) {
            log.warn("Unknown command $command (${BotManage.getConfigKey(context.botId)}) , skip processing")
            return
        }
        EventBus.postAsync(
            RouteCalledEvent(
                botId = context.botId,
                groupId = context.groupId,
                senderId = context.senderId,
                input = context.parsedMessage.content,
                hit = cmd
            )
        )
    }
}
