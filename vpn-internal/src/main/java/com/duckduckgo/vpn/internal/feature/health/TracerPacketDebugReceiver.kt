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

package com.duckduckgo.vpn.internal.feature.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.duckduckgo.di.scopes.AppObjectGraph
import com.duckduckgo.mobile.android.vpn.health.PacketTracedEvent
import com.duckduckgo.mobile.android.vpn.health.TracedState.ADDED_TO_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.service.VpnQueues
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.service.VpnStopReason
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import xyz.hexene.localvpn.Packet
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * This receiver allows to send a diagnostic packet through the system. This can be used as an indicator of app (bad) health
 *
 * $ adb shell am broadcast -a tracer
 */
class TracerPacketDebugReceiver(
    context: Context,
    intentAction: String = ACTION,
    private val receiver: (Intent) -> Unit
) : BroadcastReceiver() {

    init {
        kotlin.runCatching { context.unregisterReceiver(this) }
        context.registerReceiver(this, IntentFilter(intentAction))
    }

    override fun onReceive(context: Context, intent: Intent) {
        receiver(intent)
    }

    companion object {
        private const val ACTION = "tracer"

        fun ruleIntent(): Intent {
            return Intent(ACTION)
        }
    }
}

@ContributesMultibinding(AppObjectGraph::class)
class TracerPacketDebugReceiverRegister @Inject constructor(
    private val context: Context,
    private val vpnQueues: VpnQueues
) : VpnServiceCallbacks {

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        Timber.i("Debug receiver %s registered", TracerPacketDebugReceiver::class.java.simpleName)

        TracerPacketDebugReceiver(context) { intent ->
            Timber.w("Injecting tracer packet")
            val tracerPacket = buildTracerPacket()
            vpnQueues.tcpDeviceToNetwork.offer(tracerPacket)
            tracerPacket.tracerFlow.add(PacketTracedEvent(ADDED_TO_DEVICE_TO_NETWORK_QUEUE))
        }
    }

    private fun buildTracerPacket(): Packet {
        val byteBuffer = ByteBuffer.allocateDirect(16384)
        byteBuffer.put(-1)
        return Packet(byteBuffer)
    }

    override fun onVpnStopped(coroutineScope: CoroutineScope, vpnStopReason: VpnStopReason) {
        Timber.i("Debug receiver %s stopping", TracerPacketDebugReceiver::class.java.simpleName)
    }
}
