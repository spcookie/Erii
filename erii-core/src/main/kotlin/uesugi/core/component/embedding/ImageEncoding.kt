package uesugi.core.component.embedding

import kotlin.io.encoding.Base64

fun ByteArray.toDataUrl(): String {
    val base64 = Base64.encode(this)
    val mimeType = detectImageMimeType()
    return "data:$mimeType;base64,$base64"
}

private fun ByteArray.detectImageMimeType(): String {
    if (size < 4) throw IllegalArgumentException("Unsupported image type: data too short")

    // PNG: 89 50 4E 47
    if (this[0] == 0x89.toByte() && this[1] == 0x50.toByte() && this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()) {
        return "image/png"
    }

    // JPEG: FF D8 FF
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }

    // GIF: 47 49 46 38 (GIF8)
    if (this[0] == 0x47.toByte() && this[1] == 0x49.toByte() && this[2] == 0x46.toByte() && this[3] == 0x38.toByte()) {
        return "image/gif"
    }

    throw IllegalArgumentException("Unsupported image type: unknown magic bytes")
}
