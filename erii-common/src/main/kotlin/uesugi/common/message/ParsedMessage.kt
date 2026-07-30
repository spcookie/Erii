package uesugi.common.message

import uesugi.common.data.MessageType

data class ParsedMessage(
    val content: String,
    val isAtBot: Boolean,
    val messageType: MessageType,
    val imageUrl: String? = null,
    val imageFormat: String? = null,
    val audioUrl: String? = null,
    val audioFormat: String? = null
)
