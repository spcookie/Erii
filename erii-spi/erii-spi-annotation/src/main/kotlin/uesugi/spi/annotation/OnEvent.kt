package uesugi.spi.annotation

import uesugi.common.event.integration.IntegrationEvent
import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnEvent(val value: KClass<out IntegrationEvent>)
