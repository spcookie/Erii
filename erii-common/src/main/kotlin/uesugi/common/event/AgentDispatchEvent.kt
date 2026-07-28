package uesugi.common.event

sealed interface AgentDispatchEvent {
    val botId: String
    val groupId: String
    val echo: String
}