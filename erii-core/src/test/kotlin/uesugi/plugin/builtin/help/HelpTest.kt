package uesugi.plugin.builtin.help

import uesugi.plugin.PluginHelpCatalog
import uesugi.plugin.PluginHelpItem
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HelpTest {

    @Test
    fun `render help page selects jte template and passes catalog`() {
        val catalog = PluginHelpCatalog(
            commands = listOf(PluginHelpItem("status", "查看运行状态")),
            routes = listOf(PluginHelpItem("CHAT", "处理日常对话")),
        )

        val content = renderHelpPage(catalog)

        assertEquals("help.kte", content.template)
        assertSame(catalog, content.params["catalog"])
    }

    @Test
    fun `help template renders command and route sections`() {
        val template = Path.of("src/main/jte/help.kte").readText()

        assertTrue(template.contains("@param catalog: PluginHelpCatalog"))
        assertTrue(template.contains("@for(item in catalog.commands)"))
        assertTrue(template.contains("@for(item in catalog.routes)"))
        assertTrue(template.contains("<section class=\"directory commands\">"))
        assertTrue(template.contains("<section class=\"directory routes\">"))
        assertTrue(template.contains("grid-template-columns: repeat(3, minmax(0, 1fr));"))
        assertTrue(template.contains("grid-template-columns: repeat(2, minmax(0, 1fr));"))
        assertTrue(template.contains("<span class=\"command-mark\">/</span>${'$'}{item.name}"))
        assertTrue(template.contains("<span class=\"route-mark\">${'$'}</span>${'$'}{item.name}"))
        assertTrue(template.contains("<article class=\"entry command-entry\">"))
        assertTrue(template.contains("<article class=\"entry route-entry\">"))
        assertTrue(template.contains("${'$'}{item.description.trim()}"))
        assertTrue(template.contains("No description available."))
        assertTrue(template.contains("Aliases · ${'$'}{item.aliases.joinToString(\" · \")}"))
        assertTrue(template.contains("@if(catalog.commands.size % 3 == 1)"))
        assertTrue(template.contains("--accent: #C5E803;"))
        assertTrue(template.contains("--accent-on: #0a0a0a;"))
        assertFalse(template.contains("class=\"card"))
        assertFalse(template.contains("lang=\"zh-CN\""))
        assertFalse(template.contains("trigger", ignoreCase = true))
        assertFalse(template.contains("#002fa7", ignoreCase = true))
        assertFalse(template.contains("linear-gradient"))
        assertFalse(template.contains("box-shadow"))
        assertFalse(template.contains("border-radius"))
    }
}
