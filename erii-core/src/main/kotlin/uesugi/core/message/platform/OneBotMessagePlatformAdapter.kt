package uesugi.core.message.platform

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uesugi.common.data.Channel
import uesugi.common.data.MessageType
import uesugi.common.message.MessagePlatformAdapter
import uesugi.common.message.ParsedMessage
import uesugi.onebot.core.message.*
import uesugi.onebot.core.model.GroupMessageEvent
import uesugi.onebot.core.model.MessageContent
import uesugi.onebot.core.model.MessageEvent
import uesugi.onebot.core.model.MessageSentEvent
import uesugi.onebot.core.model.PrivateMessageEvent

class OneBotMessagePlatformAdapter : MessagePlatformAdapter<MessageEvent> {

    override fun extractRawGroupId(event: MessageEvent): String = when (event) {
        is GroupMessageEvent -> event.groupId.toString()
        is PrivateMessageEvent -> Channel.privateChannelId(event.userId)
        is MessageSentEvent -> throw IllegalArgumentException("MessageSentEvent must be converted before reaching adapter")
    }

    override fun extractSenderId(event: MessageEvent): String = when (event) {
        is GroupMessageEvent -> event.userId.toString()
        is PrivateMessageEvent -> event.userId.toString()
        is MessageSentEvent -> throw IllegalArgumentException("MessageSentEvent must be converted before reaching adapter")
    }

    override fun extractSenderNick(event: MessageEvent): String = when (event) {
        is GroupMessageEvent -> event.sender.card.ifBlank { event.sender.nickname }
        is PrivateMessageEvent -> event.sender.nickname
        is MessageSentEvent -> throw IllegalArgumentException("MessageSentEvent must be converted before reaching adapter")
    }

    override suspend fun parseMessage(event: MessageEvent, botId: String): ParsedMessage = when (event) {
        is GroupMessageEvent -> parseMessageSegments(event.message, botId, isPrivate = false)
        is PrivateMessageEvent -> parseMessageSegments(event.message, botId, isPrivate = true)
        is MessageSentEvent -> throw IllegalArgumentException("MessageSentEvent must be converted before reaching adapter")
    }

    private suspend fun parseMessageSegments(
        message: MessageContent,
        botId: String,
        isPrivate: Boolean,
    ): ParsedMessage {
        var isAtBot = isPrivate
        var imageUrl: String? = null
        var imageFormat: String? = null
        var audioUrl: String? = null
        var audioFormat: String? = null
        var hasMedia = false
        var messageType = MessageType.TEXT

        val content = buildString {
            for (segment in message) {
                when (segment.type) {
                    "at" -> {
                        if (!isPrivate && !isAtBot && segment.atQq?.toString() == botId) {
                            isAtBot = true
                        }
                        append("@${segment.atQq}")
                    }

                    "image" -> {
                        if (!hasMedia) {
                            hasMedia = true
                            messageType = MessageType.IMAGE
                            imageUrl = segment.imageUrl ?: segment.imageFile
                            imageFormat = inferMediaFormat(
                                supportedFormats = IMAGE_FORMATS,
                                segment.imageUrl,
                                segment.imageFile,
                            )
                        }
                        append(segment.data["summary"]?.jsonPrimitive?.contentOrNull ?: "[图片]")
                    }

                    "record" -> {
                        if (!hasMedia) {
                            hasMedia = true
                            messageType = MessageType.AUDIO
                            audioUrl = segment.recordUrl ?: segment.recordFile
                            audioFormat = inferMediaFormat(
                                supportedFormats = AUDIO_FORMATS,
                                segment.recordUrl,
                                segment.recordFile,
                            )
                        }
                        append("[音频]")
                    }

                    "text" -> {
                        segment.text?.let { append(it) }
                    }

                    "face" -> {
                        val face = segment.data["row"]?.jsonObject["faceText"]?.jsonPrimitive?.contentOrNull ?: ""
                        append("[${face.removePrefix("/")}]")
                    }

                    "reply" -> {
                        append("[引用消息 id: ${segment.replyId}]")
                    }

                    "markdown" -> {
                        append(segment.data["content"]?.jsonPrimitive?.contentOrNull ?: "")
                    }

                    else -> append("[${segment.type}]")
                }
            }
        }

        return ParsedMessage(
            content = content,
            isAtBot = isAtBot,
            messageType = messageType,
            imageUrl = imageUrl,
            imageFormat = imageFormat,
            audioUrl = audioUrl,
            audioFormat = audioFormat
        )
    }

    private fun inferMediaFormat(
        supportedFormats: Set<String>,
        vararg sources: String?,
    ): String? = sources.firstNotNullOfOrNull { source ->
        val cleanSource = source
            ?.substringBefore("?")
            ?.substringBefore("#")
            ?.removePrefix("file://")
            ?: return@firstNotNullOfOrNull null
        if (cleanSource.startsWith("base64://")) {
            return@firstNotNullOfOrNull null
        }
        val extension = cleanSource.substringAfterLast(".", missingDelimiterValue = "")
            .lowercase()
        extension.takeIf { it in supportedFormats }
    }

    private companion object {
        val IMAGE_FORMATS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        val AUDIO_FORMATS = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "webm", "amr", "silk")
    }
}

/**
 * 对字符串做 CQ 码 value 转义。
 * `&` → `&amp;`, `[` → `&#91;`, `]` → `&#93;`, `,` → `&#44;`
 */
internal fun String.toCQEscaped(): String = buildString {
    val input = this@toCQEscaped
    for (i in input.indices) {
        when (input[i]) {
            '&' -> append("&amp;")
            '[' -> append("&#91;")
            ']' -> append("&#93;")
            ',' -> append("&#44;")
            else -> append(input[i])
        }
    }
}
