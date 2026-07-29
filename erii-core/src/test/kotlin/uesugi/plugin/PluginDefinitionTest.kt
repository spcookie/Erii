package uesugi.plugin

import uesugi.spi.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginDefinitionTest {
    @Test
    fun `command route key is resolved lazily after extension configuration loads`() {
        val extension = DynamicCommand()
        val definition = buildPluginDef(extension)

        extension.command = "configured-command"

        assertEquals(
            listOf("configured-command"),
            definition.routeKeys.filterIsInstance<CmdRouteKey>().map { it.key },
        )
        assertEquals(1, extension.commandReads)

        extension.command = "later-command"
        assertEquals(
            listOf("configured-command"),
            definition.routeKeys.filterIsInstance<CmdRouteKey>().map { it.key },
        )
        assertEquals(1, extension.commandReads)
    }

    @Test
    fun `agent extension description defaults to empty`() {
        assertEquals("", DynamicCommand().description)
    }

    private class DynamicCommand : CmdExtension<Unit, ArgParserHolder.Empty, TestPlugin> {
        var command: String = "default-command"
        var commandReads: Int = 0
            private set

        override val cmd: String
            get() {
                commandReads++
                return command
            }

        override fun onLoad(context: PluginContext) = Unit
    }

    private class TestPlugin : AgentPlugin()
}
