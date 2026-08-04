package uesugi.common.event.agent

sealed interface AgentDispatchEvent {
    val botId: String
    val groupId: String
    val echo: String
}