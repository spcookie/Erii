package uesugi.core.state.emotion

import uesugi.common.data.PAD

data class EmotionChangeEvent(
    val botId: String,
    val groupId: String,
    val pad: PAD
)