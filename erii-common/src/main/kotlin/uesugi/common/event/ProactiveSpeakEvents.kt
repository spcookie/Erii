package uesugi.common.event

import ai.koog.agents.core.tools.reflect.ToolSet
import uesugi.common.ChatToolSet
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class ProactiveSpeakEvent(
    val botId: String,
    private val _groupId: String,
    val senderId: String? = null,
    val interruptionMode: InterruptionMode,
    val input: String? = null,
    val chatVision: Boolean = false,
    val webSearch: Boolean = false,
    val toolSetBuilder: ((ChatToolSet) -> List<ToolSet>)? = null,
    val feature: ProactiveSpeakFeature = PSFeature.NONE,
    val echo: String = Uuid.random().toHexString(),
) {
    val groupId: String get() = _groupId
}

enum class InterruptionMode {
    Interrupt, IceBreak, Routine
}

object PSFeature {
    const val NONE = 0x0
    const val IGNORE_INTERRUPT = 0x1
    const val GRAB = 0x2
    const val FALLBACK = 0x4
    const val CHAT_URGENT = 0x8
}

typealias ProactiveSpeakFeature = Int

infix fun ProactiveSpeakFeature?.has(flag: ProactiveSpeakFeature): Boolean {
    return this?.let { it and flag != 0 } ?: false
}