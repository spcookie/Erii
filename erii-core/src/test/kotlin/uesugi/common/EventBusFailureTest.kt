package uesugi.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventBusFailureTest {
    @Test
    fun `throwing sync dispatch reports failures after notifying remaining subscribers`() {
        val calls = mutableListOf<String>()
        val failing = EventBus.subscribeSync<TestEvent> {
            calls += "failing"
            error("subscriber failed")
        }
        val succeeding = EventBus.subscribeSync<TestEvent> {
            calls += "succeeding"
        }
        try {
            val failure = assertFailsWith<EventDispatchException> {
                EventBus.postSyncOrThrow(TestEvent)
            }

            assertEquals(listOf("failing", "succeeding"), calls)
            assertEquals("subscriber failed", failure.cause?.message)
        } finally {
            EventBus.unsubscribeSync(failing)
            EventBus.unsubscribeSync(succeeding)
        }
    }
}

private data object TestEvent
