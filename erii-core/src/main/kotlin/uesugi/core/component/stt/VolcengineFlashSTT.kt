package uesugi.core.component.stt

import com.fasterxml.jackson.databind.JsonNode
import com.google.auto.service.AutoService
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import uesugi.common.extend.ISTT
import uesugi.common.toolkit.ConfigHolder
import uesugi.config.HttpClientFactory
import java.io.IOException
import java.util.Base64
import java.util.UUID

@AutoService(ISTT::class)
class VolcengineFlashSTT : ISTT {

    override val id: String = "volcengine-flash"

    override suspend fun transcribe(
        audio: ByteArray,
        format: String,
        fileName: String,
    ): String {
        require(audio.isNotEmpty()) { "Audio must not be empty" }
        require(audio.size <= MAX_AUDIO_SIZE_BYTES) {
            "Audio exceeds the Volcengine 100 MB limit"
        }

        val apiKey = ConfigHolder.getSttApiKey()
        require(apiKey.isNotBlank()) { "STT API key is not configured" }

        val requestId = UUID.randomUUID().toString()
        val endpoint = ConfigHolder.getSttUrl().ifBlank { DEFAULT_ENDPOINT }
        val resourceId = ConfigHolder.getSttModel().ifBlank { DEFAULT_RESOURCE_ID }
        val requestBody = buildVolcengineFlashRequest(audio, format, fileName)

        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            header(X_API_KEY, apiKey)
            header(X_API_RESOURCE_ID, resourceId)
            header(X_API_REQUEST_ID, requestId)
            header(X_API_SEQUENCE, FINAL_SEQUENCE)
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            }
            setBody(requestBody)
        }

        val statusCode = response.headers[X_API_STATUS_CODE]
        val message = response.headers[X_API_MESSAGE]
        val logId = response.headers[X_TT_LOGID]

        if (response.status != HttpStatusCode.OK || isApiFailure(statusCode, message)) {
            throw IOException(
                buildString {
                    append("Volcengine STT request failed")
                    append(": HTTP ${response.status.value}")
                    statusCode?.let { append(", status=$it") }
                    message?.let { append(", message=$it") }
                    logId?.let { append(", logId=$it") }
                }
            )
        }

        return parseVolcengineFlashText(response.body())
    }

    private companion object {
        private const val DEFAULT_ENDPOINT =
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        private const val DEFAULT_RESOURCE_ID = "volc.bigasr.auc_turbo"
        private const val FINAL_SEQUENCE = "-1"
        private const val MAX_AUDIO_SIZE_BYTES = 100 * 1024 * 1024
        private const val REQUEST_TIMEOUT_MILLIS = 10 * 60 * 1000L

        private const val X_API_KEY = "X-Api-Key"
        private const val X_API_RESOURCE_ID = "X-Api-Resource-Id"
        private const val X_API_REQUEST_ID = "X-Api-Request-Id"
        private const val X_API_SEQUENCE = "X-Api-Sequence"
        private const val X_API_STATUS_CODE = "X-Api-Status-Code"
        private const val X_API_MESSAGE = "X-Api-Message"
        private const val X_TT_LOGID = "X-Tt-Logid"

        private val client = HttpClientFactory().createClient()
    }
}

internal fun buildVolcengineFlashRequest(
    audio: ByteArray,
    format: String,
    fileName: String,
): Map<String, Any> {
    val normalizedFormat = normalizeVolcengineAudioFormat(format, fileName)
    val audioRequest = mutableMapOf<String, Any>(
        "data" to Base64.getEncoder().encodeToString(audio),
        "format" to normalizedFormat,
    )
    if (normalizedFormat == "ogg") {
        audioRequest["codec"] = "opus"
    }

    return mapOf(
        "audio" to audioRequest,
        "request" to mapOf(
            "model_name" to "bigmodel",
            "enable_itn" to true,
            "enable_punc" to true,
            "show_utterances" to true,
        ),
    )
}

internal fun normalizeVolcengineAudioFormat(format: String, fileName: String): String {
    val candidate = format.trim().lowercase().ifBlank {
        fileName.substringAfterLast('.', "").lowercase()
    }
    val normalized = when (candidate) {
        "mpeg" -> "mp3"
        "wave" -> "wav"
        "opus" -> "ogg"
        else -> candidate
    }
    require(normalized in VOLCENGINE_AUDIO_FORMATS) {
        "Unsupported Volcengine STT audio format: ${candidate.ifBlank { "unknown" }}"
    }
    return normalized
}

internal fun parseVolcengineFlashText(root: JsonNode): String {
    val result = root.path("result")
    val text = result.path("text")
    if (!text.isMissingNode && !text.isNull && text.isTextual) {
        return text.asText()
    }

    val utterances = result.path("utterances")
    if (utterances.isArray) {
        return utterances
            .mapNotNull { utterance ->
                utterance.path("text")
                    .takeIf { it.isTextual }
                    ?.asText()
            }
            .joinToString(separator = "")
    }

    throw IOException("Volcengine STT response is missing result.text: ${root.toString().take(2_000)}")
}

internal fun isApiFailure(statusCode: String?, message: String?): Boolean {
    if (message != null) return !message.equals("OK", ignoreCase = true)
    return statusCode != null && statusCode != "20000000"
}

private val VOLCENGINE_AUDIO_FORMATS = setOf(
    "raw",
    "wav",
    "mp3",
    "ogg",
    "pcm",
    "spx",
    "amr",
    "aac",
    "m4a",
)
