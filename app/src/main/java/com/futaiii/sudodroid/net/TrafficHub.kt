package com.futaiii.sudodroid.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

data class RatePoint(val up: Long, val down: Long)

/** Live throughput and session totals from the core's `/traffic` stream. */
data class TrafficSnapshot(
    val live: Boolean = false,
    val up: Long = 0,
    val down: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val history: List<RatePoint> = emptyList()
) {
    fun next(sample: TrafficSample): TrafficSnapshot {
        val point = RatePoint(sample.up, sample.down)
        val trimmed = if (history.size >= HISTORY_SIZE) history.drop(history.size - HISTORY_SIZE + 1) else history
        return TrafficSnapshot(
            live = true,
            up = sample.up,
            down = sample.down,
            upTotal = sample.upTotal,
            downTotal = sample.downTotal,
            history = trimmed + point
        )
    }

    companion object {
        const val HISTORY_SIZE = 60
    }
}

/**
 * Shares one `/traffic` stream between every consumer (screen, notification). The stream is open only
 * while somebody collects [snapshots], so a hidden app with the screen off keeps no connection busy.
 */
object TrafficHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshots: StateFlow<TrafficSnapshot> = ControllerApi.endpoint
        .flatMapLatest { endpoint ->
            if (endpoint == null) {
                flowOf(TrafficSnapshot())
            } else {
                ControllerApi.trafficSamples(endpoint)
                    .scan(TrafficSnapshot(live = true)) { acc, sample -> acc.next(sample) }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 3_000), TrafficSnapshot())
}
