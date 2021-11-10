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

import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.di.scopes.VpnObjectGraph
import com.duckduckgo.mobile.android.vpn.di.VpnCoroutineScope
import com.duckduckgo.mobile.android.vpn.di.VpnScope
import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState.*
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.service.VpnStopReason
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.random.Random

@VpnScope
@ContributesMultibinding(VpnObjectGraph::class)
class AppTPHealthMonitor @Inject constructor(
    private val healthMetricCounter: HealthMetricCounter,
    @VpnCoroutineScope private val coroutineScope: CoroutineScope) :
    VpnServiceCallbacks {

    private val _healthState = MutableSharedFlow<HealthState>()
    val healthState : SharedFlow<HealthState> = _healthState
    private val monitoringJob = ConflatedJob()

    private suspend fun checkCurrentHealth() {
        if(Random.nextBoolean()) {
            _healthState.emit(GoodHealth)
        }  else {
            _healthState.emit(BadHealth)
        }
    }

    fun startMonitoring() {
        monitoringJob += coroutineScope.launch {
            while(isActive) {
                checkCurrentHealth()
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob.cancel()
    }

    companion object {
        private const val MONITORING_INTERVAL_MS: Long = 1_000
    }

    sealed class HealthState {
        object Initializing: HealthState()
        object GoodHealth: HealthState()
        object BadHealth: HealthState()
    }

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        startMonitoring()
    }

    override fun onVpnStopped(coroutineScope: CoroutineScope, vpnStopReason: VpnStopReason) {
        stopMonitoring()
    }
}