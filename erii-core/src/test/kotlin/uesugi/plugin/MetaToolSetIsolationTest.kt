package uesugi.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import uesugi.common.IBotManage
import uesugi.spi.Meta
import uesugi.spi.MetaToolSet
import uesugi.spi.annotation.useToolMeta
import uesugi.spi.annotation.withMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class MetaToolSetIsolationTest {
    @Test
    fun `tool set instances keep independent bot and group metadata`() {
        val groupA = TestMeta(botId = "bot", groupId = "group-a")
        val groupB = TestMeta(botId = "bot", groupId = "group-b")

        val toolSetA = BoundMetaToolSet(groupA)
        val toolSetB = BoundMetaToolSet(groupB)

        assertNotSame(toolSetA.meta, toolSetB.meta)
        assertEquals("group-a", toolSetA.meta.groupId)
        assertEquals("group-b", toolSetB.meta.groupId)
    }

    @Test
    fun `tool metadata stays isolated across concurrent coroutines`() = runBlocking {
        val groupA = TestMeta(botId = "bot", groupId = "group-a")
        val groupB = TestMeta(botId = "bot", groupId = "group-b")
        val groupAEntered = CompletableDeferred<Unit>()
        val releaseGroupA = CompletableDeferred<Unit>()

        val resultA = async(Dispatchers.Default) {
            withMeta(groupA) {
                groupAEntered.complete(Unit)
                releaseGroupA.await()
                withContext(Dispatchers.IO) {
                    useToolMeta().value.groupId
                }
            }
        }

        groupAEntered.await()
        val resultB = async(Dispatchers.Default) {
            withMeta(groupB) {
                useToolMeta().value.groupId
            }
        }

        assertEquals("group-b", resultB.await())
        releaseGroupA.complete(Unit)
        assertEquals("group-a", resultA.await())
    }
}

private class BoundMetaToolSet(override val meta: Meta) : MetaToolSet

private data class TestMeta(
    override val botId: String,
    override val groupId: String,
    override val input: String? = null,
    override val senderId: String? = null,
    override val echo: String? = null,
) : Meta {
    override val roledBot: IBotManage.RoledBot
        get() = error("Not needed by metadata isolation tests")
}
