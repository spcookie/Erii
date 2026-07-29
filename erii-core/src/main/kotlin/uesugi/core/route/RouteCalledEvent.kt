package uesugi.core.route

import uesugi.common.route.CmdRouteRule
import uesugi.common.route.LLMRouteRule
import uesugi.common.route.RouteRule
import uesugi.spi.CmdRouteKey
import uesugi.spi.LLMRouteKey
import uesugi.spi.RouteKey
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class RouteCalledEvent(
    val botId: String,
    val groupId: String,
    val senderId: String,
    val input: String,
    val hit: RouteRule,
    val echo: String = Uuid.random().toHexString(),
) {
    infix fun hit(rule: RouteRule): Boolean {
        return this.hit == rule
    }

    infix fun hit(key: RouteKey): Boolean {
        return when (key) {
            is LLMRouteKey -> {
                this.hit is LLMRouteRule && this.hit.name.equals(key.key, ignoreCase = true)
            }

            is CmdRouteKey -> {
                this.hit is CmdRouteRule && this.hit.name.equals(key.key, ignoreCase = true)
            }
        }
    }
}