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

import xyz.hexene.localvpn.Packet
import java.text.NumberFormat

data class PacketTracedEvent(val event: TracedState, val timestampNanos: Long = System.nanoTime())

enum class TracedState {
    CREATED,
    ADDED_TO_DEVICE_TO_NETWORK_QUEUE,
    REMOVED_FROM_DEVICE_TO_NETWORK_QUEUE
}

fun Packet.describeTracerFlow(): String {
    if (tracerFlow == null) {
        return "Not a tracer packet"
    }

    val startTime = this.tracerFlow?.firstOrNull()
    if (startTime?.event != TracedState.CREATED) {
        return "No CREATED timestamp available; invalid"
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
