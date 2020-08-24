/*
 * Copyright (c) 2020 DuckDuckGo
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

package com.duckduckgo.mobile.android.vpn.processor.tcp

import com.duckduckgo.mobile.android.vpn.processor.tcp.ConnectionInitializer.TcpConnectionParams
import com.duckduckgo.mobile.android.vpn.service.NetworkChannelCreator
import com.duckduckgo.mobile.android.vpn.service.VpnQueues
import timber.log.Timber
import xyz.hexene.localvpn.Packet
import xyz.hexene.localvpn.Packet.TCPHeader
import xyz.hexene.localvpn.TCB
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.random.Random

interface ConnectionInitializer {
    fun initializeConnection(params: TcpConnectionParams): Pair<TCB, SocketChannel>?

    data class TcpConnectionParams(
        val destinationAddress: String,
        val destinationPort: Int,
        val sourcePort: Int,
        val packet: Packet,
        val responseBuffer: ByteBuffer
    ) {

        fun key(): String {
            return "$destinationAddress:$destinationPort:$sourcePort"
        }

    }
}

class TcpConnectionInitializer(private val queues: VpnQueues, private val networkChannelCreator: NetworkChannelCreator) : ConnectionInitializer {

    override fun initializeConnection(params: TcpConnectionParams): Pair<TCB, SocketChannel>? {
        val key = params.key()

        val header = params.packet.tcpHeader
        params.packet.swapSourceAndDestination()

        Timber.d("Initializing connection $key")

        if (header.isSYN) {
            val channel = networkChannelCreator.createSocket()
            val sequenceNumber = Random.nextLong(Short.MAX_VALUE.toLong() + 1)
            val sequenceFromPacket = header.sequenceNumber
            val ackNumber = header.sequenceNumber + 1
            val ackFromPacket = header.acknowledgementNumber

            val tcb = TCB(
                key,
                sequenceNumber,
                sequenceFromPacket,
                ackNumber,
                ackFromPacket,
                channel,
                params.packet
            )
            TCB.putTCB(params.key(), tcb)
            channel.connect(InetSocketAddress(params.destinationAddress, params.destinationPort))
            return Pair(tcb, channel)
        } else {
            Timber.i("Trying to initialize a connection but is not a SYN packet; sending RST")
            params.packet.updateTcpBuffer(params.responseBuffer, TCPHeader.RST.toByte(), 0, params.packet.tcpHeader.sequenceNumber + 1, 0)
            queues.networkToDevice.offer(params.responseBuffer)
            return null
        }
    }

//    private fun connect(tcb: TCB, channel: SocketChannel, params: TcpConnectionParams) {
//        channel.connect(InetSocketAddress(params.destinationAddress, params.destinationPort))
//        if (channel.finishConnect()) {
//            Timber.v("Channel finished connecting to ${tcb.ipAndPort}")
//            tcb.status = TCB.TCBStatus.SYN_RECEIVED
//            Timber.v("Update TCB ${tcb.ipAndPort} status: ${tcb.status}")
//            params.packet.updateTcpBuffer(params.responseBuffer, (TCPHeader.SYN or TCPHeader.ACK).toByte(), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0)
//            tcb.mySequenceNum++
//            queues.networkToDevice.offer(params.responseBuffer)
//        } else {
//            Timber.v("Not finished connecting yet to ${tcb.selectionKey}, will register for OP_CONNECT event")
//            tcb.status = TCB.TCBStatus.SYN_SENT
//            Timber.v("Update TCB ${tcb.ipAndPort} status: ${tcb.status}")
//            selector.wakeup()
//            tcb.selectionKey = channel.register(selector, SelectionKey.OP_CONNECT, tcb)
//        }
//    }
}
