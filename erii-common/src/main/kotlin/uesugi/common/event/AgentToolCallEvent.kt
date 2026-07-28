package uesugi.common.event

import ai.koog.serialization.JSONObject

sealed interface AgentToolCallEvent {
    val botId: String
    val groupId: String
    val echo: String
    val toolName: String
    val toolArgs: JSONObject
}