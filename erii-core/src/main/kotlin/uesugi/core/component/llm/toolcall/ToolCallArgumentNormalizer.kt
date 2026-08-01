package uesugi.core.component.llm.toolcall

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Tool Call 参数的纯字符串规范化工具，不依赖 Client 或 Executor。 */
internal object ToolCallArgumentNormalizer {

    /** 解开历史消息中被重复 JSON 序列化的 Tool Call 参数。 */
    fun normalizeRequest(arguments: String): String {
        val decoded = runCatching {
            Json.decodeFromString<String>(arguments)
        }.getOrNull() ?: return arguments

        return decoded.takeIf(::isJsonObject) ?: arguments
    }

    /** 尝试修复模型响应中未转义引号等不合法 JSON 内容。 */
    fun repairResponse(arguments: String): String {
        val normalized = normalizeRequest(arguments)
        if (isJsonObject(normalized)) return normalized

        val repaired = escapeUnescapedStringContent(normalized)
        return repaired.takeIf(::isJsonObject) ?: arguments
    }

    private fun isJsonObject(value: String): Boolean = runCatching {
        Json.parseToJsonElement(value).jsonObject
    }.isSuccess

    private fun escapeUnescapedStringContent(value: String): String = buildString(value.length) {
        var insideString = false
        var escaped = false

        value.forEachIndexed { index, char ->
            if (!insideString) {
                append(char)
                if (char == '"') insideString = true
                return@forEachIndexed
            }

            if (escaped) {
                append(char)
                escaped = false
                return@forEachIndexed
            }

            when (char) {
                '\\' -> {
                    append(char)
                    escaped = true
                }

                '"' -> {
                    val next = value.asSequence()
                        .drop(index + 1)
                        .firstOrNull { !it.isWhitespace() }
                    if (next == null || next in JSON_STRING_TERMINATORS) {
                        append(char)
                        insideString = false
                    } else {
                        append("\\\"")
                    }
                }

                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private val JSON_STRING_TERMINATORS = setOf(':', ',', '}', ']')
}

internal fun Message.Assistant.mapToolCallArguments(
    transform: (toolName: String, arguments: String) -> String,
): Message.Assistant = copy(
    parts = parts.map { part ->
        if (part is MessagePart.Tool.Call) {
            part.copy(args = transform(part.tool, part.args))
        } else {
            part
        }
    },
)
