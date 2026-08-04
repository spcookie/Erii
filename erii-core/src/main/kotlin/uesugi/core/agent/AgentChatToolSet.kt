package uesugi.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uesugi.common.ChatMessage
import uesugi.common.ChatToolSet
import uesugi.common.data.Channel
import uesugi.common.toolkit.logger
import uesugi.onebot.core.message.buildMessage
import uesugi.onebot.core.model.MessageContent
import uesugi.onebot.sdk.client.OneBotClient
import uesugi.onebot.sdk.client.api.canSendImage
import uesugi.onebot.sdk.client.api.canSendMarkdown
import uesugi.onebot.sdk.client.api.sendGroupMsg
import uesugi.onebot.sdk.client.api.sendPrivateMsg
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.*
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds

class AgentChatToolSet(
    val client: OneBotClient,
    val channelId: String,
    val context: Context,
    private val rateLimiter: MessageSendRateLimiter = MessageSendRateLimiter()
) : ChatToolSet {

    private val isPrivate: Boolean get() = Channel.isPrivate(channelId)

    private val log = logger()

    companion object {
        private val GIF87A = "GIF87a".toByteArray()
        private val GIF89A = "GIF89a".toByteArray()
    }

    @ChatMessage
    override suspend fun sendText(texts: List<String>): String {
        try {
            for (text in texts) {
                sendMessage(buildMessage { text(text) })
            }
        } catch (e: Exception) {
            log.error(
                "LLM tool sendText failed: channel={}, messageCount={}, totalChars={}",
                channelId,
                texts.size,
                texts.sumOf { it.length },
                e
            )
            return "消息发送失败，原因：" + e.message
        }

        return "发送文本消息成功"
    }

    @ChatMessage
    override suspend fun sendMeme(tag: String, alt: String): String {
        if (!canSendImage()) {
            sendText(listOf(alt))
            return "当前不支持发送图片，已发送文字替代：$alt"
        }
        try {
            val memo = context.meme(tag)
            if (memo != null) {
                val imageBytes = convertNonGifToGif(memo.bytes)
                val base64 = Base64.getEncoder().encodeToString(imageBytes)
                sendMessage(buildMessage {
                    image("base64://$base64")
                })
                return "发送表情包消息成功"
            } else {
                sendText(listOf(alt))
                return "未找到表情包\"$tag\"，已发送文字替代"
            }
        } catch (e: Exception) {
            log.error("LLM tool sendMeme failed: channel={}, tag={}", channelId, tag.take(64), e)
            return "发送表情包消息失败，原因：" + e.message
        }
    }

    private suspend fun convertNonGifToGif(bytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (bytes.isGif()) {
            return@withContext bytes
        }

        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
            ?: error("Unable to read non-GIF meme image")
        val output = ByteArrayOutputStream()
        val written = output.use { ImageIO.write(image, "gif", it) }
        if (!written) {
            error("GIF image encoding is not supported")
        }
        output.toByteArray()
    }

    private fun ByteArray.isGif(): Boolean {
        return size >= GIF87A.size &&
                (startsWith(GIF87A) || startsWith(GIF89A))
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) {
            return false
        }
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) {
                return false
            }
        }
        return true
    }

    @ChatMessage
    override suspend fun sendImageByUrl(url: String): String {
        if (!canSendImage()) {
            return "当前不支持发送图片，请使用 sendText 发送纯文本消息代替。图片 URL: $url"
        }
        val isImg = isImageUrl(url)
        if (!isImg) {
            return "URL 链接访问不是一个图片"
        }

        try {
            sendMessage(buildMessage {
                image(file = url, url = url)
            })
        } catch (e: Exception) {
            log.error("LLM tool sendImageByUrl failed: channel={}, url={}", channelId, urlForLog(url), e)
            return "发送图片失败，原因：" + e.message
        }

        return "发送图片成功"
    }

    private suspend fun isImageUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

            val contentType = conn.contentType ?: return@withContext false

            contentType.startsWith("image/")
        } catch (e: Exception) {
            log.warn("LLM tool sendImageByUrl URL check failed: channel={}, url={}", channelId, urlForLog(url), e)
            false
        }
    }

    @ChatMessage
    override suspend fun sendAtAndText(
        userIds: List<Long>,
        text: String?
    ): String {
        if (isPrivate) {
            if (text == null) return "私聊中已忽略 @ 提及，且没有要发送的文本。"
            return sendText(listOf(text)).let { "私聊中已忽略 @ 提及: $it" }
        }
        try {
            val msg = buildMessage {
                for (userId in userIds) {
                    at(userId)
                }
                text?.let { text(it) }
            }
            sendMessage(msg)
        } catch (e: Exception) {
            log.error(
                "LLM tool sendAtAndText failed: channel={}, userCount={}, textChars={}",
                channelId,
                userIds.size,
                text?.length ?: 0,
                e
            )
            return "发送消息失败，原因：" + e.message
        }

        return "发送消息成功"
    }

    @ChatMessage
    override suspend fun sendAtAll(): String {
        if (isPrivate) {
            return "私聊模式下不支持 @全体成员。"
        }
        try {
            sendMessage(buildMessage { atAll() })
        } catch (e: Exception) {
            log.error("LLM tool sendAtAll failed: channel={}", channelId, e)
            return "发送 At 全体成员消息失败， 原因：" + e.message
        }

        return "发送 At 全体成员消息成功"
    }

    @ChatMessage
    override suspend fun sendMarkdown(content: String): String {
        if (canSendMarkdown()) {
            try {
                sendMessage(buildMessage { markdown(content) })
                return "发送 Markdown 消息成功"
            } catch (e: Exception) {
                log.error(
                    "LLM tool sendMarkdown failed: channel={}, contentChars={}",
                    channelId,
                    content.length,
                    e
                )
                return "发送 Markdown 消息失败，原因：" + e.message
            }
        }
        sendMessage(buildMessage { text(content) })
        return "当前不支持 Markdown，已降级为文本发送"
    }

    private suspend fun canSendImage(): Boolean = try {
        client.canSendImage()
    } catch (e: Exception) {
        log.warn("Failed to query image capability: channel={}", channelId, e)
        false
    }

    private suspend fun canSendMarkdown(): Boolean = try {
        client.canSendMarkdown()
    } catch (e: Exception) {
        log.warn("Failed to query Markdown capability: channel={}", channelId, e)
        false
    }

    private fun urlForLog(url: String): String = url
        .substringBefore('?')
        .substringBefore('#')
        .take(300)

    private suspend fun sendMessage(message: MessageContent) {
        rateLimiter.awaitTurn()
        if (isPrivate) {
            val userId = Channel.extractUserId(channelId)
                ?: error("Invalid private channel ID: $channelId")
            client.sendPrivateMsg(userId, message)
        } else {
            client.sendGroupMsg(channelId.toLong(), message)
        }
    }

}

class MessageSendRateLimiter(
    private val intervalMs: Long = DEFAULT_SEND_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it.milliseconds) }
) {
    private val mutex = Mutex()
    private var lastSentAtMs: Long? = null

    suspend fun awaitTurn() = mutex.withLock {
        val lastSentAt = lastSentAtMs
        if (lastSentAt != null) {
            val waitMs = intervalMs - (nowMillis() - lastSentAt)
            if (waitMs > 0) {
                delayMillis(waitMs)
            }
        }
        lastSentAtMs = nowMillis()
    }

    companion object {
        const val DEFAULT_SEND_INTERVAL_MS = 1240L
    }

}
