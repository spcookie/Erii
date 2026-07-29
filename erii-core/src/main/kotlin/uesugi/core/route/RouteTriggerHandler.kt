package uesugi.core.route

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import uesugi.common.EventBus
import uesugi.common.event.route.CallRouteEvent
import uesugi.common.toolkit.StrGlob
import uesugi.common.toolkit.logger

object RouteTriggerHandler {

    private val log = logger()

    private val scope =
        CoroutineScope(Job() + CoroutineName("route-trigger-handler-") + CoroutineExceptionHandler { _, throwable ->
            log.error("Exception while handling route-trigger-handler.", throwable)
        })

    fun start() {
        EventBus.subscribeAsync<CallRouteEvent>(scope) { event ->
            val rule = RouteRuleRegister.getRulesForBot(event.botId)
                .firstOrNull { rule -> StrGlob.matchesIgnoreCase(event.hit.name, rule.name) }
            if (rule == null) {
                log.warn("No rule matched for ${event.hit.name}")
                return@subscribeAsync
            }
            log.info("Rule matched: ${rule.name}, glob: ${event.hit.name}")
            EventBus.postAsync(
                RouteCalledEvent(
                    botId = event.botId,
                    groupId = event.groupId,
                    senderId = event.senderId,
                    input = event.input,
                    hit = event.hit,
                    echo = event.echo
                )
            )
        }
    }
}