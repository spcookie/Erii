package uesugi.common.event.route

import uesugi.common.route.RouteRule
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class CallRouteEvent(
    val botId: String,
    val groupId: String,
    val senderId: String,
    val input: String,
    val hit: RouteRule,
    val echo: String = Uuid.random().toHexString(),
)