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

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.di.scopes.VpnObjectGraph
import com.duckduckgo.mobile.android.vpn.R
import com.duckduckgo.mobile.android.vpn.di.VpnCoroutineScope
import com.duckduckgo.mobile.android.vpn.health.AppTPHealthMonitor.HealthState.*
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.service.VpnStopReason
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@ContributesMultibinding(VpnObjectGraph::class)
class AppTPHealthMonitor @Inject constructor(
    private val healthMetricCounter: HealthMetricCounter,
    private val healthClassifier: HealthClassifier,
    @VpnCoroutineScope private val coroutineScope: CoroutineScope,
    private val applicationContext: Context
) :
    VpnServiceCallbacks {

    private val _healthState = MutableStateFlow<HealthState>(Initializing)
    val healthState: StateFlow<HealthState> = _healthState
    private val monitoringJob = ConflatedJob()

    private var shouldShowNotifications: Boolean = true
    private var simulatedGoodHealth: Boolean? = null

    private var tunReadQueueReadAlertDuration: Int = 0

    private suspend fun checkCurrentHealth() {
        if (simulatedGoodHealth == true) {
            Timber.i("Pretending good health")
            _healthState.emit(GoodHealth)
            hideBadHealthNotification()
            return
        } else if (simulatedGoodHealth == false) {
            Timber.i("Pretending bad health")
            _healthState.emit(BadHealth)
            showBadHealthNotification()
            return
        }

//        store raw metrics as they happen.
//            - sample the health regularly (every 10s?)
//            - every sample, get stats from last 10s, and produce a single RATE
//            - don't store the rate. but instead check if the rate is acceptable. if it is, all is well.
//            - if the rate is not acceptable, flag it but don't yet raise the alarm
//            - next health sample, check rate again. if it's fine now, remove the flag (false alarm)
//            - if the rate is still not good after N samples, the system is now in bad health. Raise the alarm.


        val tunReads = healthMetricCounter.getStat(SimpleEvent.TUN_READ())
        val readFromNetworkQueue = healthMetricCounter.getStat(SimpleEvent.REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE())

        when (healthClassifier.determineHealthTunInputQueueReadRatio(tunReads, readFromNetworkQueue)) {
            is GoodHealth -> tunReadQueueReadAlertDuration = 0
            is BadHealth -> tunReadQueueReadAlertDuration++
            else -> tunReadQueueReadAlertDuration = 0
        }

        var badHealthFlag = false
        if (tunReadQueueReadAlertDuration == 0) {
            Timber.i("tunReadQueueRate is fine)")
        } else if (tunReadQueueReadAlertDuration >= 5) {
            Timber.i("tunReadQueueReadAlertDuration remained elevated across %d samples. this is bad health sign", tunReadQueueReadAlertDuration)
            badHealthFlag = true
        } else {
            Timber.i("tunReadQueueReadAlertDuration elevated across %d samples.", tunReadQueueReadAlertDuration)
        }

        if (badHealthFlag) {
            Timber.i("App health check caught some problem(s)")
            _healthState.emit(BadHealth)
            showBadHealthNotification()
        } else {
            Timber.i("App health check is good")
            _healthState.emit(GoodHealth)
            hideBadHealthNotification()
        }
    }

    private suspend fun showBadHealthNotification() {
        if (!shouldShowNotifications) {
            Timber.v("Not showing health notifications.")
            return
        }

        val target = Intent().also {
            it.setClassName(applicationContext.packageName, "com.duckduckgo.vpn.internal.feature.health.VpnDiagnosticsActivity")
        }

        val pendingIntent = TaskStackBuilder.create(applicationContext)
            .addNextIntentWithParentStack(target)
            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(applicationContext, "notificationid")
            .setSmallIcon(R.drawable.ic_baseline_mood_bad_24)
            .setContentTitle("hello")
            .setContentText("it looks like the VPN Service might be in bad health")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()

        withContext(Dispatchers.Main) {
            NotificationManagerCompat.from(applicationContext).let { nm ->

                val channelBuilder = NotificationChannelCompat.Builder("notificationid", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("notificationid")
                nm.createNotificationChannel(channelBuilder.build())

                nm.notify(BAD_HEALTH_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun hideBadHealthNotification() {
        NotificationManagerCompat.from(applicationContext).cancel(BAD_HEALTH_NOTIFICATION_ID)
    }

    fun startMonitoring() {
        monitoringJob += coroutineScope.launch {
            while (isActive) {
                checkCurrentHealth()
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob.cancel()
    }

    fun toggleNotifications(shouldShowNotifications: Boolean) {
        this.shouldShowNotifications = shouldShowNotifications
    }

    companion object {
        private const val MONITORING_INTERVAL_MS: Long = 1_000

        private const val BAD_HEALTH_NOTIFICATION_ID = 9890
    }

    sealed class HealthState {
        object Initializing : HealthState()
        object GoodHealth : HealthState()
        object BadHealth : HealthState()
    }

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        startMonitoring()
    }

    override fun onVpnStopped(coroutineScope: CoroutineScope, vpnStopReason: VpnStopReason) {
        stopMonitoring()
    }

    fun simulateHealthState(goodHealth: Boolean?) {
        this.simulatedGoodHealth = goodHealth
    }

    sealed class AlertType {
        object None : AlertType()
        data class Elevated(val elevatedDuration: Int) : AlertType()
    }
}
