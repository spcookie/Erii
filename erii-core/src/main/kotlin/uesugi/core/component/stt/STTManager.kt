package uesugi.core.component.stt

import uesugi.common.extend.ISTT
import uesugi.common.toolkit.ConfigHolder
import java.util.*

object STTManager {
    private val providers: Map<String, ISTT> by lazy {
        ServiceLoader.load(ISTT::class.java).associateBy { it.id }
    }

    fun get(): ISTT {
        val id = ConfigHolder.getSttProvider()
        return providers[id] ?: error("No STT provider found for id: $id, available: ${providers.keys}")
    }
}
