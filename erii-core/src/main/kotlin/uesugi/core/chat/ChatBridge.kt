package uesugi.core.chat

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import uesugi.common.BotManage
import uesugi.common.data.HistoryRecord
import uesugi.common.data.MessageType
import uesugi.common.data.ResourceRecord
import uesugi.common.toolkit.logger
import uesugi.config.ChatBridgeConst.MOCK_BOT_ID
import uesugi.config.ChatBridgeConst.MOCK_CONFIG_KEY
import uesugi.config.ChatBridgeConst.MOCK_GROUP_ID
import uesugi.config.ChatBridgeConst.MOCK_USER_ID
import uesugi.core.MessageEventListener
import uesugi.core.bot.BotRoleManager
import uesugi.core.message.history.HistoryService
import uesugi.core.message.platform.toCQEscaped
import uesugi.onebot.core.config.OneBotConfig
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.core.message.text
import uesugi.onebot.core.model.GroupMessageEvent
import uesugi.onebot.core.model.MessageContent
import uesugi.onebot.mock.MockBot
import uesugi.onebot.mock.storage.InMemoryStorage
import uesugi.onebot.sdk.client.OneBotClient
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class ChatHistoryEntry(
    val id: Long,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val messageType: MessageType,
    val hasImage: Boolean,
)

class ChatBridge(
    private val historyService: HistoryService
) {

    companion object {
        val MOCK_WS_PORT_RANGE = 6701..6710
        const val HISTORY_LIMIT = 50

        private val log = logger()

        private fun findAvailablePort(): Int {
            for (port in MOCK_WS_PORT_RANGE) {
                var ss: ServerSocket? = null
                try {
                    ss = ServerSocket(port)
                    return port
                } catch (_: Exception) {
                    // port in use, try next
                } finally {
                    ss?.close()
                }
            }
            throw IllegalStateException("No available port in range $MOCK_WS_PORT_RANGE")
        }
    }

    private lateinit var mockBot: MockBot
    private lateinit var client: OneBotClient

    private var wsPort = 0
    private var messageListener: MessageEventListener? = null
    private val pendingResponseId = AtomicReference<String?>(null)

    @Volatile
    var wsResponseCallback: (suspend (String?, String) -> Unit)? = null

    data class HistoryResult(
        val entries: List<ChatHistoryEntry>,
        val hasMore: Boolean
    )

    fun getHistory(beforeId: Long?, limit: Int = HISTORY_LIMIT): HistoryResult {
        val botId = MOCK_BOT_ID.toString()
        val groupId = MOCK_GROUP_ID.toString()
        val (records, hasMore) = historyService.getHistoryByGroupCursor(
            botId = botId,
            groupId = groupId,
            beforeId = beforeId?.toInt(),
            limit = limit
        )
        return HistoryResult(
            entries = records.map { it.toChatHistoryEntry() }.reversed(),
            hasMore = hasMore
        )
    }

    fun getHistoryImage(historyId: Int): ResourceRecord? =
        historyService.getHistoryById(historyId)?.chatImageResourceOrNull()

    suspend fun start() {
        wsPort = findAvailablePort()
        log.info("ChatBridge: starting mock bot on port $wsPort")

        val mockConfig = OneBotConfig(
            wsForwardServerEnable = true,
            wsForwardServerHost = "127.0.0.1",
            wsForwardServerPort = wsPort,
            selfId = MOCK_BOT_ID,
            appName = "chat-bridge-mock"
        )
        mockBot = MockBot(mockConfig, InMemoryStorage(selfId = MOCK_BOT_ID))

        mockBot.addGroup(MOCK_GROUP_ID, "CLI Chat")
        mockBot.addUser(MOCK_USER_ID, "You")
        mockBot.addGroupMember(MOCK_GROUP_ID, MOCK_BOT_ID, "Erii")
        mockBot.addGroupMember(MOCK_GROUP_ID, MOCK_USER_ID, "You")

        mockBot.onBotSendGroupMsg = { event ->
            val text = extractResponse(event)
            if (text.isNotBlank()) {
                val requestId = pendingResponseId.getAndSet(null)?.takeIf { it.isNotBlank() }
                wsResponseCallback?.invoke(requestId, text)
            }
        }

        mockBot.start()
        log.info("ChatBridge: mock bot started")

        val clientConfig = OneBotConfig(
            wsForwardClientEnable = true,
            wsForwardClientUseUniversal = true,
            wsForwardClientUrl = "ws://127.0.0.1:$wsPort"
        )
        client = OneBotClient(clientConfig)
        client.start()

        BotManage.registerBot(MOCK_CONFIG_KEY, client, MOCK_BOT_ID.toString(), BotRoleManager.getDefaultRole())
        log.info("ChatBridge: OneBotClient connected to mock bot")
    }

    fun selectRole(roleId: String) {
        check(isReady()) { "ChatBridge is not ready yet" }
        val role = BotRoleManager.getRole(roleId)
            ?: throw IllegalArgumentException("Role not found: $roleId")
        BotManage.refreshBotRole(MOCK_CONFIG_KEY, role)
        mockBot.addGroupMember(MOCK_GROUP_ID, MOCK_BOT_ID, role.name)
        registerListener(role.name)
        log.info("ChatBridge: role set to ${role.id} (${role.name})")
    }

    private fun registerListener(roleName: String) {
        if (messageListener != null) {
            messageListener!!.roleName = roleName
            return
        }
        messageListener = MessageEventListener(
            botId = MOCK_BOT_ID.toString(),
            botConfigKey = MOCK_CONFIG_KEY,
            initialRoleName = roleName,
        ).also { it.register(client) }
    }

    suspend fun sendMessage(requestId: String?, text: String) {
        if (text.isBlank()) return

        val responseId = requestId.orEmpty()
        pendingResponseId.set(responseId)
        val message: MessageContent = buildMessage {
            if (!text.startsWith("/")) {
                at(MOCK_BOT_ID)
            }
            text(text)
        }
        try {
            mockBot.simulateGroupMessage(MOCK_GROUP_ID, MOCK_USER_ID, message)
        } catch (e: Exception) {
            pendingResponseId.compareAndSet(responseId, null)
            throw e
        }
    }

    suspend fun stop() {
        log.info("ChatBridge: stopping")
        pendingResponseId.set(null)
        if (::client.isInitialized) {
            client.stop()
        }
        if (::mockBot.isInitialized) {
            mockBot.stop()
        }
    }

    fun isReady(): Boolean = ::mockBot.isInitialized && ::client.isInitialized

    private fun extractResponse(event: GroupMessageEvent): String {
        if (event.rawMessage.isNotBlank()) return event.rawMessage
        val sb = StringBuilder()
        for (segment in event.message) {
            when (segment.type) {
                "text" -> segment.text?.let { if (it.isNotBlank()) sb.append(it) }
                "image" -> sb.append("[图片]")
                "record", "voice" -> sb.append("[语音]")
                "markdown" -> {
                    val raw = segment.data["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (raw.isNotBlank()) sb.append("[CQ:markdown,content=${raw.toCQEscaped()}]")
                }
            }
        }
        return sb.toString()
    }
}

internal fun HistoryRecord.toChatHistoryEntry(): ChatHistoryEntry {
    val sender = when (userId) {
        MOCK_USER_ID.toString() -> "user"
        else -> "bot"
    }
    val timestamp = createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    return ChatHistoryEntry(
        id = (id ?: 0).toLong(),
        sender = sender,
        content = toChatHistoryCqContent(sender),
        timestamp = timestamp,
        messageType = messageType,
        hasImage = chatImageResourceOrNull() != null,
    )
}

internal fun HistoryRecord.chatImageResourceOrNull(): ResourceRecord? =
    takeIf {
        botId == MOCK_BOT_ID.toString() &&
                groupId == MOCK_GROUP_ID.toString()
    }?.resource?.takeIf {
        it.botId == MOCK_BOT_ID.toString() && it.groupId == MOCK_GROUP_ID.toString()
    }

internal fun String.removeMockBotMention(): String {
    val mention = "@$MOCK_BOT_ID"
    if (!startsWith(mention)) return this
    return removePrefix(mention).trimStart()
}

private fun HistoryRecord.toChatHistoryCqContent(sender: String): String {
    val text = if (sender == "user") {
        content.orEmpty().removeMockBotMention()
    } else {
        content.orEmpty()
    }
    val image = chatImageResourceOrNull() ?: return text
    return text.withHistoryImageCqCode(historyImageCqCode(image))
}

private fun HistoryRecord.historyImageCqCode(resource: ResourceRecord): String {
    val historyId = id?.takeIf { it > 0 }
    val file = historyId?.let { "erii-history://$it" } ?: resource.url
    return "[CQ:image,file=${file.toCQEscaped()},historyId=${historyId?.toString().orEmpty().toCQEscaped()}]"
}

private fun String.withHistoryImageCqCode(imageCq: String): String {
    val placeholder = "[图片]"
    val index = indexOf(placeholder)
    if (index < 0) {
        return if (isBlank()) imageCq else "$this $imageCq"
    }
    return replaceRange(index, index + placeholder.length, imageCq)
}
