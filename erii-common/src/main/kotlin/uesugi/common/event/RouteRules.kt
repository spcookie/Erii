package uesugi.common.event

interface RouteRule {
    val name: String
}

data class LLMRouteRule(override val name: String, val description: String) : RouteRule

data class CmdRouteRule(override val name: String) : RouteRule