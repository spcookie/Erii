package uesugi.config

import uesugi.common.data.Channel

object ChatBridgeConst {
    const val MOCK_BOT_ID = 0L
    const val MOCK_CONFIG_KEY = "chat-bridge-mock"
    const val MOCK_CHAT_USER_ID = 1L
    val MOCK_PRIVATE_CHANNEL_ID: String = Channel.privateChannelId(MOCK_CHAT_USER_ID)
}