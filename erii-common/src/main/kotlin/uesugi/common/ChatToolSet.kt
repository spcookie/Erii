package uesugi.common

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

interface ChatToolSet : ToolSet {
    @ChatMessage
    @Tool
    @LLMDescription("发送纯文本消息。群聊时发送到群里，私聊时发送到对话中。")
    suspend fun sendText(@LLMDescription("分段文本消息") texts: List<String>): String

    @ChatMessage
    @Tool
    @LLMDescription("发送表情包消息。")
    suspend fun sendMeme(
        @LLMDescription("表情包标签。用于向量匹配的语义标签。2-6 个字的抽象语义。") tag: String,
        @LLMDescription("表情包替代文本。若匹配不到表情包时发送的替代文本。必须是自然语言句子。") alt: String
    ): String

    @ChatMessage
    @Tool
    @LLMDescription("发送图片消息。")
    suspend fun sendImageByUrl(@LLMDescription("发送图片的 URL") url: String): String

    @ChatMessage
    @Tool
    @LLMDescription("发送 At 消息和文本消息。私聊时将忽略 @ 直接发送文本。")
    suspend fun sendAtAndText(
        @LLMDescription("At 的用户 ID") userIds: List<Long>,
        @LLMDescription("文本消息") text: String?
    ): String

    @ChatMessage
    @Tool
    @LLMDescription("发送 At 全体成员消息。私聊时不可用。")
    suspend fun sendAtAll(): String

    @ChatMessage
    @Tool
    @LLMDescription("发送 Markdown 消息。支持 Markdown 语法的富文本消息。")
    suspend fun sendMarkdown(@LLMDescription("Markdown 内容") content: String): String

}