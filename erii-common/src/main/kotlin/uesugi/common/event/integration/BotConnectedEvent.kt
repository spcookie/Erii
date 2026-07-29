package uesugi.common.event

import uesugi.common.IntegrationEvent

data class BotConnectedEvent(
    val botId: String,
    val configKey: String,
    val roleName: String
) : IntegrationEvent
