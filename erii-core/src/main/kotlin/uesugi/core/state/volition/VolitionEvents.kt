package uesugi.core.state.volition


sealed class VolitionEvent {
    abstract val botId: String
    abstract val groupId: String
}

data class ResetStimulusEvent(
    override val botId: String,
    override val groupId: String,
    val stimulus: Double = 15.0
) : VolitionEvent()

data class KeywordHitEvent(
    override val botId: String,
    override val groupId: String,
    val stimulus: Double = 30.0
) : VolitionEvent()

data class BusyGroupEvent(
    override val botId: String,
    override val groupId: String,
    val stimulus: Double = 10.0
) : VolitionEvent()

data class IndirectMentionEvent(
    override val botId: String,
    override val groupId: String,
    val stimulus: Double = 25.0
) : VolitionEvent()

data class EmotionalResonanceEvent(
    override val botId: String,
    override val groupId: String,
    val stimulus: Double = 15.0
) : VolitionEvent()
