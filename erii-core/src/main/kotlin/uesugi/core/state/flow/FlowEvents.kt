package uesugi.core.state.flow

sealed class FlowEvent {
    abstract val botId: String
    abstract val groupId: String
}

data class CoreInterestEvent(
    override val botId: String,
    override val groupId: String,
    val interest: Double = 1.0
) : FlowEvent()

data class ContinuousInteractionEvent(
    override val botId: String,
    override val groupId: String,
    val momentum: Double = 1.0
) : FlowEvent()

data class DeepReplyEvent(
    override val botId: String,
    override val groupId: String,
    val baseCharge: Double = 5.0
) : FlowEvent()

data class GroupResonanceEvent(
    override val botId: String,
    override val groupId: String,
    val globalArousal: Double = 0.5
) : FlowEvent()

data class NegativeEvent(
    override val botId: String,
    override val groupId: String,
    val penalty: Double = 40.0
) : FlowEvent()

data class TopicInterruptEvent(
    override val botId: String,
    override val groupId: String,
    val penalty: Double = 30.0,
    val matchScore: Double = 0.0
) : FlowEvent()

data class LowActivityEvent(
    override val botId: String,
    override val groupId: String,
    val penalty: Double = 5.0
) : FlowEvent()

data class RepeatTopicEvent(
    override val botId: String,
    override val groupId: String,
    val penalty: Double = 10.0
) : FlowEvent()

data class FlowChangeEvent(
    val botId: String,
    val groupId: String,
    val value: Double = 0.0
)