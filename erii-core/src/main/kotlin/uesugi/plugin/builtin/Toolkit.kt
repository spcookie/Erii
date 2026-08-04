package uesugi.plugin.builtin

import uesugi.common.data.Channel
import uesugi.onebot.sdk.client.api.getGroupList
import uesugi.onebot.sdk.client.api.getStrangerInfo
import uesugi.spi.Meta

internal suspend fun resolveGroupName(meta: Meta): String {
    if (Channel.isPrivate(meta.groupId)) {
        val userId = Channel.extractUserId(meta.groupId)!!
        return meta.roledBot.refBot.getStrangerInfo(userId).nickname
    }
    return runCatching {
        meta.roledBot.refBot.getGroupList()
            .find { it.groupId.toString() == meta.groupId }
            ?.groupName
    }.getOrNull() ?: meta.groupId
}
