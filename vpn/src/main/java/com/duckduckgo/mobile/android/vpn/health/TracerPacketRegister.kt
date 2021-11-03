/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.vpn.health

import kotlinx.coroutines.asCoroutineDispatcher
import timber.log.Timber
import java.text.NumberFormat
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TracerPacketRegister @Inject constructor(/*@VpnCoroutineScope private val coroutineScope: CoroutineScope*/) {

    private val tracers: MutableMap<String, MutableList<TracerEvent>> = HashMap()
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun logEvent(event: TracerEvent) {
        // coroutineScope.launch(dispatcher) {
        Timber.v("Registering %s for tracer %s", event.event, event.tracerId)

        val id = event.tracerId

        addEvent(id, event)

        Timber.e("state for tracer packet %s\n\n%s", id, describe(id))
        // }
    }

    @Synchronized
    private fun addEvent(id: String, event: TracerEvent) {
        val events = getEvents(id)
        events.add(event)
        tracers[id] = events
    }

    private fun getEvents(id: String): MutableList<TracerEvent> {
        val existing = tracers[id]
        if (existing != null) return existing

        Timber.i("no existing list exists for tracer %s, creating new one", id)

        val newList = mutableListOf<TracerEvent>()
        tracers[id] = newList
        return newList
    }

    fun describe(tracerId: String): String {
        val tracerFlow = tracers[tracerId] ?: return "Not found"
        val startTime = tracerFlow.firstOrNull() ?: return "No CREATED timestamp available; invalid"
        if (startTime.event != TracedState.CREATED) {
            return String.format("First event for tracer %s is not CREATED; it is %s", tracerId, startTime.event)
        }

        val numberFormatter = NumberFormat.getNumberInstance().also { it.maximumFractionDigits = 2 }

        return StringBuilder(String.format("Detailing tracer flow for %s. %d steps in flow.\n", tracerId, tracerFlow.size)).also { sb ->
            sb.append(String.format("---> CREATED at %d", startTime.timestampNanos))

            tracerFlow
                .filter { it.event != TracedState.CREATED }
                .forEach {
                    val durationFromCreation = it.timestampNanos - startTime.timestampNanos

                    sb.append("\n")

                    sb.append(
                        String.format(
                            "---> %s happened %s ms (%s ns) after it was first created (absolute time=%d)",
                            it.event,
                            numberFormatter.format(durationFromCreation / 1_000_000L.toDouble()),
                            numberFormatter.format(durationFromCreation),
                            it.timestampNanos
                        )
                    )
                }

            sb.append("\n\n")
        }.toString()
    }
}
