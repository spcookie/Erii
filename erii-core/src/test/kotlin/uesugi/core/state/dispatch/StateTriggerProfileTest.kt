package uesugi.core.state.dispatch

import uesugi.common.toolkit.StateDispatchTuningConfig
import uesugi.common.toolkit.StateTriggerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StateTriggerProfileTest {
    @Test
    fun `dispatch defaults to economy`() {
        assertEquals(StateTriggerProfile.ECONOMY, StateDispatchTuningConfig().profile)
    }

    @Test
    fun `profile parsing is case insensitive and falls back to economy`() {
        assertEquals(StateTriggerProfile.REALTIME, StateTriggerProfile.parse("realtime"))
        assertEquals(StateTriggerProfile.BALANCED, StateTriggerProfile.parse("Balanced"))
        assertEquals(StateTriggerProfile.ECONOMY, StateTriggerProfile.parse("unknown"))
        assertEquals(StateTriggerProfile.ECONOMY, StateTriggerProfile.parse(null))
    }

    @Test
    fun `realtime policies match the configured profile`() {
        assertEquals(
            expectedPolicies(15.seconds, 20, 20, 30, 30.seconds, 100),
            StateTriggerProfile.REALTIME.policies()
        )
        assertEquals(1.minutes, StateTriggerProfile.REALTIME.reconciliationInterval)
    }

    @Test
    fun `balanced policies match the configured profile`() {
        assertEquals(
            expectedPolicies(30.seconds, 30, 30, 60, 1.minutes, 200),
            StateTriggerProfile.BALANCED.policies()
        )
        assertEquals(3.minutes, StateTriggerProfile.BALANCED.reconciliationInterval)
    }

    @Test
    fun `economy policies match the configured profile`() {
        assertEquals(
            expectedPolicies(1.minutes, 50, 50, 100, 2.minutes, 300),
            StateTriggerProfile.ECONOMY.policies()
        )
        assertEquals(5.minutes, StateTriggerProfile.ECONOMY.reconciliationInterval)
    }

    private fun expectedPolicies(
        debounce: kotlin.time.Duration,
        emotionMinimum: Int,
        realtimeMinimum: Int,
        knowledgeMinimum: Int,
        memeDelay: kotlin.time.Duration,
        evolutionMinimum: Int
    ) = mapOf(
        StateWorkKind.EMOTION to StateWorkPolicy(BacklogMode.LATEST_WINDOW, debounce, emotionMinimum, 200),
        StateWorkKind.FLOW to StateWorkPolicy(BacklogMode.LATEST_WINDOW, debounce, realtimeMinimum, 100),
        StateWorkKind.VOLITION to StateWorkPolicy(BacklogMode.LATEST_WINDOW, debounce, realtimeMinimum, 100),
        StateWorkKind.MEMORY to StateWorkPolicy(BacklogMode.SEQUENTIAL, debounce, knowledgeMinimum, 400),
        StateWorkKind.SUMMARY to StateWorkPolicy(BacklogMode.SEQUENTIAL, debounce, knowledgeMinimum, 200),
        StateWorkKind.MEME_COLLECT to StateWorkPolicy(BacklogMode.SEQUENTIAL, memeDelay, 1, 500),
        StateWorkKind.MEME_ANALYZE to StateWorkPolicy(BacklogMode.SEQUENTIAL, debounce, 1, 20),
        StateWorkKind.EVOLUTION to StateWorkPolicy(BacklogMode.SEQUENTIAL, debounce, evolutionMinimum, 500)
    )
}
