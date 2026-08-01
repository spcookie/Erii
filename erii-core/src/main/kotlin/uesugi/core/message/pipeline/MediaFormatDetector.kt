package uesugi.core.message.pipeline

import okio.ByteString
import uesugi.common.data.MessageType

internal fun detectMediaFormat(content: ByteString, messageType: MessageType): String? =
    when (messageType) {
        MessageType.IMAGE -> detectImageFormat(content)
        MessageType.AUDIO -> detectAudioFormat(content)
        else -> null
    }

private fun detectImageFormat(content: ByteString): String? = when {
    content.matchesBytes(0, 0xFF, 0xD8, 0xFF) -> "jpg"
    content.matchesBytes(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
    content.matchesAscii(0, "GIF87a") || content.matchesAscii(0, "GIF89a") -> "gif"
    content.matchesAscii(0, "RIFF") && content.matchesAscii(8, "WEBP") -> "webp"
    content.matchesAscii(0, "BM") -> "bmp"
    else -> null
}

private fun detectAudioFormat(content: ByteString): String? = when {
    content.matchesAscii(0, "RIFF") && content.matchesAscii(8, "WAVE") -> "wav"
    content.matchesAscii(0, "OggS") && content.containsAscii("OpusHead", searchLimit = 128) -> "opus"
    content.matchesAscii(0, "OggS") -> "ogg"
    content.matchesAscii(0, "fLaC") -> "flac"
    content.matchesAscii(0, "#!AMR") -> "amr"
    content.matchesAscii(0, "#!SILK_V3") || content.matchesAscii(1, "#!SILK_V3") -> "silk"
    content.matchesBytes(0, 0x1A, 0x45, 0xDF, 0xA3) -> "webm"
    content.matchesAscii(4, "ftyp") -> "m4a"
    content.isAacFrame() -> "aac"
    content.matchesAscii(0, "ID3") || content.isMpegAudioFrame() -> "mp3"
    else -> null
}

private fun ByteString.matchesBytes(offset: Int, vararg expected: Int): Boolean {
    return !(offset < 0 || size < offset + expected.size) && expected.indices.all { index ->
        (this[offset + index].toInt() and 0xFF) == expected[index]
    }
}

private fun ByteString.matchesAscii(offset: Int, expected: String): Boolean {
    return !(offset < 0 || size < offset + expected.length) && expected.indices.all { index ->
        this[offset + index].toInt() == expected[index].code
    }
}

private fun ByteString.containsAscii(expected: String, searchLimit: Int): Boolean {
    val lastStart = minOf(size, searchLimit) - expected.length
    return lastStart >= 0 && (0..lastStart).any { matchesAscii(it, expected) }
}

private fun ByteString.isAacFrame(): Boolean {
    if (size < 2) return false
    val first = this[0].toInt() and 0xFF
    val second = this[1].toInt() and 0xFF
    return first == 0xFF && second and 0xF6 == 0xF0
}

private fun ByteString.isMpegAudioFrame(): Boolean {
    if (size < 2) return false
    val first = this[0].toInt() and 0xFF
    val second = this[1].toInt() and 0xFF
    return first == 0xFF && second and 0xE0 == 0xE0
}
