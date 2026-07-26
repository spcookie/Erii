package uesugi.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginRoutePrefixManagerTest {
    @Test
    fun `duplicate custom prefix falls back to plugin name`() {
        val manager = PluginRoutePrefixManager()
        val firstOwner = Any()
        val secondOwner = Any()

        assertEquals("shared", manager.register(firstOwner, "first", "shared"))
        assertEquals("second", manager.register(secondOwner, "second", "shared"))
    }

    @Test
    fun `prefix can be changed and is released on unregister`() {
        val manager = PluginRoutePrefixManager()
        val firstOwner = Any()
        val secondOwner = Any()

        assertEquals("old", manager.register(firstOwner, "first", "old"))
        assertEquals("new", manager.register(firstOwner, "first", "new"))
        assertEquals("old", manager.register(secondOwner, "second", "old"))

        manager.unregister(firstOwner)

        assertEquals("new", manager.register(secondOwner, "second", "new"))
    }

    @Test
    fun `invalid prefix falls back to plugin name`() {
        val manager = PluginRoutePrefixManager()

        assertEquals("demo", manager.register(Any(), "demo", "path/segment"))
        assertEquals("blank", manager.register(Any(), "blank", "  "))
    }
}
