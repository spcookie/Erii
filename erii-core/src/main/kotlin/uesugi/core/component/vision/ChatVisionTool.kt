package uesugi.core.component.vision

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import okio.buffer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uesugi.common.data.HistoryEntity
import uesugi.common.toolkit.logger
import uesugi.common.toolkit.ref
import uesugi.core.component.storage.ObjectStorage
import java.util.*

class ChatVisionTool(
    /** Whether the current LLM model natively supports vision (multimodal). */
    private val multimodal: Boolean = false
) : ToolSet {

    private val log = logger()
    private val objectStorage by ref<ObjectStorage>()

    @Tool
    @LLMDescription(
        """
    每当您需要分析、描述或从图像中提取信息时，都必须使用此工具，
    包括当您从用户输入或任何与任务相关的图像中获取图像时。

    一款由大语言模型 (LLM) 驱动的视觉工具，可以根据您的指令分析和解读本地文件或 URL 中的图像内容。
    仅支持 JPEG、PNG 和 WebP 格式。不支持其他格式（例如 PDF、GIF、PSD、SVG）。
    """
    )
    suspend fun understandImage(
        @LLMDescription("用于描述您希望从图像中分析或提取的内容的文本提示") prompt: String,
        @LLMDescription("图像的 ID，从聊天信息中获取") imageId: String
    ): String {
        log.info(
            "LLM tool understandImage started: imageId={}, promptChars={}",
            imageId.take(64),
            prompt.length
        )
        if (prompt.isBlank()) {
            return rejected("图像分析提示(prompt)不能为空，请提供具体的分析需求描述。")
        }
        if (imageId.isBlank()) {
            return rejected("图像ID(imageId)不能为空，请从聊天信息中获取有效的图像ID。")
        }
        val id = imageId.toIntOrNull()
            ?: return rejected("图像ID($imageId)格式无效，应为数字ID，请检查聊天信息中的图像ID。")

        return withContext(Dispatchers.IO) {
            try {
                val path = transaction {
                    HistoryEntity.findById(id)
                        ?.resource?.url
                        ?.toPath()
                }

                if (path == null) {
                    return@withContext rejected("未找到ID为 $imageId 的图像，请确认该图像是否仍在聊天上下文中。")
                }

                val bytes = objectStorage.get(path)
                    .buffer()
                    .readByteArray()
                val base64 = Base64.getEncoder().encodeToString(bytes)
                val extension = path.name.substringAfterLast(".").lowercase()
                val mimeType = when (extension) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                val imageData = "data:$mimeType;base64,$base64"
                val result = VisionManager.get().vision(prompt, imageData)
                log.info(
                    "LLM tool understandImage completed: imageId={}, mimeType={}, bytes={}, resultChars={}",
                    imageId,
                    mimeType,
                    bytes.size,
                    result.length
                )
                result
            } catch (e: Exception) {
                log.error("LLM tool understandImage failed: imageId={}", imageId.take(64), e)
                errorHint("图像分析失败：${e.message}")
            }
        }
    }

    private fun rejected(message: String): String {
        log.warn("LLM tool understandImage rejected: {}", message.take(300))
        return errorHint(message)
    }

    private fun errorHint(baseMsg: String): String {
        if (!multimodal) return baseMsg
        return buildString {
            appendLine(baseMsg)
            appendLine("[重要提示] 当前使用的模型本身已原生支持多模态视觉理解，")
            appendLine("可以直接分析收到的图片内容，无需通过此工具中转。")
            appendLine("请直接对图片进行描述、分析或回复，不要再调用 understandImage 方法。")
        }
    }
}
