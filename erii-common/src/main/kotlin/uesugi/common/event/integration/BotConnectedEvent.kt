package uesugi.common.event.integration

data class BotConnectedEvent(
    val botId: String,
    val configKey: String,
    val roleName: String
) : IntegrationEvent
