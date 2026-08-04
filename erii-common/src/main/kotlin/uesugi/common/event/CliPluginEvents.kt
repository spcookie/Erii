package uesugi.common.event

import uesugi.common.event.integration.IntegrationEvent

data class CliPluginEvent(
    val input: String,
    val echo: String,
) : IntegrationEvent

data class CliPluginReplyEvent(
    val echo: String,
    val message: String?,
) : IntegrationEvent
