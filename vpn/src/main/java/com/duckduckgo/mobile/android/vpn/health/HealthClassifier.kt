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

import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState
import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState.*
import com.duckduckgo.mobile.android.vpn.health.HealthClassifier.Companion.percentage
import javax.inject.Inject

class HealthClassifier @Inject constructor() {

    private val tunInputQueueReadHealthRule = TunInputQueueReadHealthRule()

    fun determineHealthTunInputQueueReadRatio(tunInputs: Long, queueReads: Long): HealthState {
        return tunInputQueueReadHealthRule.healthStatus(tunInputs, queueReads)
    }

    companion object {
        fun percentage(numerator: Long, denominator: Long): Double {
            if (denominator == 0L) return 0.0
            return numerator.toDouble() / denominator
        }
    }
}

class TunInputQueueReadHealthRule {
    fun healthStatus(tunInputs: Long, queueReads: Long): HealthState {
        if (tunInputs < 100) return Initializing
        return if (percentage(queueReads, tunInputs) >= 70) GoodHealth else BadHealth
    }
}
