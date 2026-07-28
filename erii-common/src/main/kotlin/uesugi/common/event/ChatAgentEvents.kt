package uesugi.common.event

import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject


data class AgentToolCallStartEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
    override val toolName: String,
    override val toolArgs: JSONObject
) : AgentToolCallEvent

data class AgentToolCallCompleteEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
    override val toolName: String,
    override val toolArgs: JSONObject,
    val toolResult: JSONElement?,
    val toolError: String?
) : AgentToolCallEvent

class AgentRunStartEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent

class AgentRunCompleteEvent(
    val throwable: Throwable?,
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent

class AgentCallFallbackEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent

class AgentCallRejectEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent


class AgentCallStartEvent(
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent

class AgentCallCompleteEvent(
    val throwable: Throwable?,
    override val botId: String,
    override val groupId: String,
    override val echo: String,
) : AgentDispatchEvent

data class ChatUrgentEvent(val urgent: ProactiveSpeakEvent)