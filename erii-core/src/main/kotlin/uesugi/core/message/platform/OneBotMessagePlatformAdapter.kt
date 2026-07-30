package uesugi.core.message.platform

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uesugi.common.data.MessageType
import uesugi.common.message.MessagePlatformAdapter
import uesugi.common.message.ParsedMessage
import uesugi.onebot.core.message.*
import uesugi.onebot.core.model.GroupMessageEvent

class OneBotMessagePlatformAdapter : MessagePlatformAdapter<GroupMessageEvent> {

    override fun extractRawGroupId(event: GroupMessageEvent): String =
        event.groupId.toString()

    override fun extractSenderId(event: GroupMessageEvent): String =
        event.userId.toString()

    override fun extractSenderNick(event: GroupMessageEvent): String =
        event.sender.card.ifBlank { event.sender.nickname }

    override suspend fun parseMessage(event: GroupMessageEvent, botId: String): ParsedMessage {
        var isAtBot = false
        var imageUrl: String? = null
        var imageFormat: String? = null
        var audioUrl: String? = null
        var audioFormat: String? = null
        var hasMedia = false
        var messageType = MessageType.TEXT

        val content = buildString {
            for (segment in event.message) {
                when (segment.type) {
                    "at" -> {
                        if (!isAtBot && segment.atQq?.toString() == botId) {
                            isAtBot = true
                        }
                        append("@${segment.atQq}")
                    }

                    "image" -> {
                        if (!hasMedia) {
                            hasMedia = true
                            messageType = MessageType.IMAGE
                            imageUrl = segment.imageUrl ?: segment.imageFile
                            imageFormat = inferMediaFormat(imageUrl, IMAGE_FORMATS)
                        }
                        append(segment.data["summary"]?.jsonPrimitive?.contentOrNull ?: "[图片]")
                    }

                    "record" -> {
                        if (!hasMedia) {
                            hasMedia = true
                            messageType = MessageType.AUDIO
                            audioUrl = segment.recordUrl ?: segment.recordFile
                            audioFormat = inferMediaFormat(audioUrl, AUDIO_FORMATS)
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

    private fun inferMediaFormat(source: String?, supportedFormats: Set<String>): String? {
        val cleanSource = source
            ?.substringBefore("?")
            ?.substringBefore("#")
            ?.removePrefix("file://")
            ?: return null
        if (cleanSource.startsWith("base64://")) {
            return null
        }
        val extension = cleanSource.substringAfterLast(".", missingDelimiterValue = "")
            .lowercase()
        return extension.takeIf { it in supportedFormats }
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
