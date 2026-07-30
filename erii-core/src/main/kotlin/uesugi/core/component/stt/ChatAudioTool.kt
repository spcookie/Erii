package uesugi.core.component.stt

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.data.MessageType
import uesugi.common.data.toRecord
import uesugi.common.toolkit.logger
import uesugi.common.toolkit.ref
import uesugi.core.component.storage.ObjectStorage

class ChatAudioTool internal constructor(
    private val nativeAudio: Boolean,
    private val objectStorage: ObjectStorage,
    private val transcriber: suspend (ByteArray, String, String) -> String,
) : ToolSet {

    constructor(nativeAudio: Boolean = false) : this(
        nativeAudio = nativeAudio,
        objectStorage = ref<ObjectStorage>().value,
        transcriber = { bytes, format, fileName ->
            STTManager.get().transcribe(bytes, format, fileName)
        },
    )

    private val log = logger()

    @Tool
    @LLMDescription(
        """
    当聊天记录中仅提供 audio_id，而您需要理解、总结或回复音频内容时，必须使用此工具。

    此工具读取聊天历史中的原始音频，并通过已配置的语音转文字服务生成转写文本。
    audio_id 必须来自聊天上下文中的 [audio_id:数字] 标记。
    """
    )
    suspend fun transcribeAudio(
        @LLMDescription("音频的 ID，从聊天信息中的 audio_id 获取") audioId: String,
    ): String {
        if (audioId.isBlank()) {
            return errorHint("音频ID(audioId)不能为空，请从聊天信息中获取有效的音频ID。")
        }
        val id = audioId.toIntOrNull()
            ?: return errorHint("音频ID($audioId)格式无效，应为数字ID，请检查聊天信息中的 audio_id。")

        return withContext(Dispatchers.IO) {
            try {
                val resource = transaction {
                    HistoryEntity.findById(id)
                        ?.takeIf { it.messageType == MessageType.AUDIO }
                        ?.resource
                        ?.toRecord()
                }

                if (resource == null) {
                    return@withContext errorHint("未找到ID为 $audioId 的音频，请确认该音频是否仍在聊天上下文中。")
                }

                val bytes = objectStorage.get(resource.url.toPath())
                    .buffer()
                    .readByteArray()
                val format = resource.fileName
                    .substringAfterLast(".", "")
                    .lowercase()
                    .ifBlank { "bin" }
                transcriber(bytes, format, resource.fileName)
            } catch (e: Exception) {
                log.warn("transcribeAudio failed", e)
                errorHint("音频转写失败：${e.message}")
            }
        }
    }

    private fun errorHint(baseMsg: String): String {
        if (!nativeAudio) return baseMsg
        return buildString {
            appendLine(baseMsg)
            appendLine("[重要提示] 当前使用的模型本身已原生支持音频理解，")
            appendLine("可以直接分析收到的音频内容，无需通过此工具中转。")
            appendLine("请直接对音频进行理解、总结或回复，不要再调用 transcribeAudio 方法。")
        }
    }
}
