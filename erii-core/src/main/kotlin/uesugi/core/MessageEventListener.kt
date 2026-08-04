package uesugi.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import org.koin.core.context.GlobalContext
import uesugi.common.message.MessageContext
import uesugi.common.toolkit.ConfigHolder
import uesugi.common.toolkit.logger
import uesugi.core.message.pipeline.MessagePipeline
import uesugi.core.message.platform.OneBotMessagePlatformAdapter
import uesugi.onebot.core.model.MessageEvent
import uesugi.onebot.sdk.client.OneBotClient
import uesugi.onebot.sdk.client.event.onGroupMessage
import uesugi.onebot.sdk.client.event.onMessageSent
import uesugi.onebot.sdk.client.event.onPrivateMessage

class MessageEventListener(
    private val botId: String,
    private val botConfigKey: String,
    initialRoleName: String = "",
) {

    @Volatile
    var roleName: String = initialRoleName

    companion object {
        private val log = logger()
    }

    private val pipeline by GlobalContext.get().inject<MessagePipeline>()
    private val adapter = OneBotMessagePlatformAdapter()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serial = mutableMapOf<String, CoroutineChannel<MessageEvent>>()

    private fun channelKey(groupId: String) = "${botId}_$groupId"

    fun register(client: OneBotClient) {
        suspend fun serialHandle(event: MessageEvent) {
            serial.computeIfAbsent(channelKey(adapter.extractRawGroupId(event))) {
                val channel = CoroutineChannel<MessageEvent>(CoroutineChannel.UNLIMITED)
                scope.launch {
                    for (event in channel) {
                        launch {
                            try {
                                handleEvent(event)
                            } catch (e: Exception) {
                                log.error("Error handling event", e)
                            }
                        }
                    }
                }
                channel
            }.send(event)
        }
        client.onGroupMessage { event ->
            serialHandle(event)
        }
        client.onPrivateMessage { event ->
            serialHandle(event)
        }
        client.onMessageSent { event ->
            event.tryAsGroupMessage()?.let { serialHandle(it) }
            event.tryAsPrivateMessage()?.let { serialHandle(it) }
        }
    }

    fun close() {
        scope.cancel()
        serial.clear()
    }

    private suspend fun handleEvent(event: MessageEvent) {
        val groupId = adapter.extractRawGroupId(event)

        if (!ConfigHolder.isGroupEnabled(botConfigKey, groupId)) return

        val context = MessageContext(
            botId = botId,
            groupId = groupId,
            senderId = adapter.extractSenderId(event),
            senderNick = adapter.extractSenderNick(event),
            parsedMessage = adapter.parseMessage(event, botId)
        )

        pipeline.process(context, roleName)
    }
}
