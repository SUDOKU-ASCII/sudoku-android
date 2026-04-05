package com.futaiii.sudodroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRuntimeSupportTest {
    @Test
    fun trafficPollDelay_isFastWhenStatsChange() {
        assertEquals(1_000L, ServiceRuntimeSupport.trafficPollDelayMs(changed = true, hasStats = true, idleCycles = 0))
    }

    @Test
    fun trafficPollDelay_backsOffAfterBriefIdle() {
        assertEquals(2_000L, ServiceRuntimeSupport.trafficPollDelayMs(changed = false, hasStats = true, idleCycles = 1))
    }

    @Test
    fun trafficPollDelay_usesLongBackoffForSustainedIdleOrNoStats() {
        assertEquals(5_000L, ServiceRuntimeSupport.trafficPollDelayMs(changed = false, hasStats = true, idleCycles = 2))
        assertEquals(5_000L, ServiceRuntimeSupport.trafficPollDelayMs(changed = false, hasStats = false, idleCycles = 0))
    }
}
