package uesugi.common.message

import uesugi.common.data.Channel

data class MessageContext(
    val botId: String,
    val groupId: String,
    val senderId: String,
    val senderNick: String,
    val parsedMessage: ParsedMessage
) {
    val isPrivate: Boolean get() = Channel.isPrivate(groupId)
    val isGroup: Boolean get() = Channel.isGroup(groupId)
}
