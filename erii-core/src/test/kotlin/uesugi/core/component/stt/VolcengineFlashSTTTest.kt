package uesugi.core.component.stt

import com.fasterxml.jackson.databind.ObjectMapper
import uesugi.common.extend.ISTT
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolcengineFlashSTTTest {

    private val mapper = ObjectMapper()

    @Test
    fun `provider is registered for service loading`() {
        val providers = ServiceLoader.load(ISTT::class.java).toList()

        assertTrue(providers.any { it is VolcengineFlashSTT && it.id == "volcengine-flash" })
    }

    @Test
    fun `request contains base64 audio data and flash model options`() {
        val request = buildVolcengineFlashRequest(
            audio = "voice".encodeToByteArray(),
            format = "mp3",
            fileName = "voice.mp3",
        )

        @Suppress("UNCHECKED_CAST")
        val audio = request.getValue("audio") as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val options = request.getValue("request") as Map<String, Any>

        assertEquals("mp3", audio["format"])
        assertEquals("dm9pY2U=", audio["data"])
        assertFalse(audio.containsKey("url"))
        assertFalse(audio.containsKey("codec"))
        assertEquals("bigmodel", options["model_name"])
        assertEquals(true, options["enable_itn"])
        assertEquals(true, options["enable_punc"])
        assertEquals(true, options["show_utterances"])
    }

    @Test
    fun `ogg request declares opus codec`() {
        val request = buildVolcengineFlashRequest(
            audio = byteArrayOf(1, 2, 3),
            format = "opus",
            fileName = "voice.opus",
        )

        @Suppress("UNCHECKED_CAST")
        val audio = request.getValue("audio") as Map<String, Any>

        assertEquals("ogg", audio["format"])
        assertEquals("opus", audio["codec"])
        assertEquals("AQID", audio["data"])
        assertFalse(audio.containsKey("url"))
    }

    @Test
    fun `format falls back to file extension and rejects unsupported values`() {
        assertEquals("wav", normalizeVolcengineAudioFormat("", "meeting.WAV"))

        assertFailsWith<IllegalArgumentException> {
            normalizeVolcengineAudioFormat("flac", "meeting.flac")
        }
    }

    @Test
    fun `response parser returns aggregate text`() {
        val root = mapper.readTree(
            """
            {
              "result": {
                "text": "你好，世界。",
                "utterances": [{"text": "你好，"}, {"text": "世界。"}]
              }
            }
            """.trimIndent()
        )

        assertEquals("你好，世界。", parseVolcengineFlashText(root))
    }

    @Test
    fun `response parser falls back to utterances`() {
        val root = mapper.readTree(
            """
            {"result": {"utterances": [{"text": "第一句。"}, {"text": "第二句。"}]}}
            """.trimIndent()
        )

        assertEquals("第一句。第二句。", parseVolcengineFlashText(root))
    }

    @Test
    fun `api headers identify failures`() {
        assertFalse(isApiFailure("20000000", "OK"))
        assertFalse(isApiFailure("unexpected-success-code", "OK"))
        assertFalse(isApiFailure(null, null))
        assertTrue(isApiFailure("45000000", "Invalid request"))
        assertTrue(isApiFailure("20000000", "Failed"))
    }
}
