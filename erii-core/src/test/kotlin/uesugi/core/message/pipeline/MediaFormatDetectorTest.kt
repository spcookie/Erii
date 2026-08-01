package uesugi.core.message.pipeline

import okio.ByteString.Companion.toByteString
import uesugi.common.data.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaFormatDetectorTest {

    @Test
    fun `detects supported image signatures`() {
        val samples = mapOf(
            "jpg" to bytes(0xFF, 0xD8, 0xFF, 0xE0),
            "png" to bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "gif" to "GIF89a".encodeToByteArray(),
            "webp" to "RIFF0000WEBP".encodeToByteArray(),
            "bmp" to "BM".encodeToByteArray(),
        )

        samples.forEach { (format, content) ->
            assertEquals(format, detectMediaFormat(content.toByteString(), MessageType.IMAGE))
        }
    }

    @Test
    fun `detects supported audio signatures`() {
        val samples = mapOf(
            "wav" to "RIFF0000WAVE".encodeToByteArray(),
            "opus" to "OggS0000OpusHead".encodeToByteArray(),
            "ogg" to "OggS0000vorbis".encodeToByteArray(),
            "flac" to "fLaC".encodeToByteArray(),
            "amr" to "#!AMR\n".encodeToByteArray(),
            "silk" to byteArrayOf(0x02) + "#!SILK_V3".encodeToByteArray(),
            "webm" to bytes(0x1A, 0x45, 0xDF, 0xA3),
            "m4a" to "0000ftypM4A ".encodeToByteArray(),
            "aac" to bytes(0xFF, 0xF1, 0x50, 0x80),
            "mp3" to bytes(0x49, 0x44, 0x33, 0x04),
        )

        samples.forEach { (format, content) ->
            assertEquals(format, detectMediaFormat(content.toByteString(), MessageType.AUDIO))
        }
    }

    @Test
    fun `returns null for unknown content or non media message`() {
        val content = "unknown".encodeToByteArray().toByteString()

        assertNull(detectMediaFormat(content, MessageType.IMAGE))
        assertNull(detectMediaFormat(content, MessageType.AUDIO))
        assertNull(detectMediaFormat(content, MessageType.TEXT))
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }
}
