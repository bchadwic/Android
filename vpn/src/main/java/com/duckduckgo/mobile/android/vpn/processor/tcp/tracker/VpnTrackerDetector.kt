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

package com.duckduckgo.mobile.android.vpn.processor.tcp.tracker

import com.duckduckgo.mobile.android.vpn.processor.tcp.tracker.RequestTrackerType.Tracker
import xyz.hexene.localvpn.TCB


interface VpnTrackerDetector {

    fun determinePacketType(tcb: TCB, hostName: String?): RequestTrackerType
}

class DomainBasedTrackerDetector(
) : VpnTrackerDetector {

    override fun determinePacketType(tcb: TCB, hostName: String?): RequestTrackerType {
        if(hostName == "example.com") return Tracker

        return RequestTrackerType.NotTracker
    }

}