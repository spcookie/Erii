package uesugi.common.extend

interface ISTT {
    val id: String

    suspend fun transcribe(
        audio: ByteArray,
        format: String,
        fileName: String,
    ): String
}
