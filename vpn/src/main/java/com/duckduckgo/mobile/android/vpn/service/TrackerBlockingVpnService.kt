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

package com.duckduckgo.mobile.android.vpn.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationManagerCompat
import com.duckduckgo.mobile.android.vpn.model.VpnTrackerAndCompany
import com.duckduckgo.mobile.android.vpn.model.dateOfPreviousMidnight
import com.duckduckgo.mobile.android.vpn.processor.TunPacketReader
import com.duckduckgo.mobile.android.vpn.processor.TunPacketWriter
import com.duckduckgo.mobile.android.vpn.processor.tcp.TcpPacketProcessor
import com.duckduckgo.mobile.android.vpn.processor.tcp.tracker.LocalIpAddressDetector
import com.duckduckgo.mobile.android.vpn.processor.tcp.tracker.VpnTrackerDetector
import com.duckduckgo.mobile.android.vpn.processor.udp.UdpPacketProcessor
import com.duckduckgo.mobile.android.vpn.stats.AppTrackerBlockingStatsRepository
import com.duckduckgo.mobile.android.vpn.store.PacketPersister
import com.duckduckgo.mobile.android.vpn.store.VpnDatabase
import com.duckduckgo.mobile.android.vpn.ui.notification.VpnNotificationBuilder.Companion.buildPersistentNotification
import dagger.android.AndroidInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import xyz.hexene.localvpn.Packet
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.*
import java.util.concurrent.*
import javax.inject.Inject

class TrackerBlockingVpnService : VpnService(), CoroutineScope by MainScope(), NetworkChannelCreator {

    @Inject
    lateinit var packetPersister: PacketPersister

    @Inject
    lateinit var trackerDetector: VpnTrackerDetector

    @Inject
    lateinit var vpnDatabase: VpnDatabase

    @Inject
    lateinit var repository: AppTrackerBlockingStatsRepository

    @Inject
    lateinit var notificationManager: NotificationManagerCompat

    @Inject
    lateinit var localAddressDetector: LocalIpAddressDetector

    private val queues = VpnQueues()

    private var tunInterface: ParcelFileDescriptor? = null

    private val binder: VpnServiceBinder = VpnServiceBinder()

    lateinit var udpPacketProcessor: UdpPacketProcessor

    lateinit var tcpPacketProcessor: TcpPacketProcessor
    private var executorService: ExecutorService? = null

    private var newTrackerObserverJob: Job? = null
    private var timeRunningTrackerJob: Job? = null

    private var lastSavedTimestamp = 0L

    inner class VpnServiceBinder : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == LAST_CALL_TRANSACTION) {
                onRevoke()
                return true
            }
            return false
        }

        fun getService(): TrackerBlockingVpnService {
            return this@TrackerBlockingVpnService
        }
    }

    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AndroidInjection.inject(this)

        udpPacketProcessor = UdpPacketProcessor(queues, this, packetPersister)
        tcpPacketProcessor = TcpPacketProcessor(queues, this, trackerDetector, packetPersister, localAddressDetector)

        Timber.i("VPN onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        Timber.i("VPN onBind invoked")
        return binder
    }

    override fun onUnbind(p0: Intent?): Boolean {
        Timber.i("VPN onUnbind invoked")
        return super.onUnbind(p0)
    }

    override fun onDestroy() {
        Timber.i("VPN onDestroy")
        notifyVpnStopped()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("onStartCommand: ${intent?.action}")

        var returnCode: Int = Service.START_NOT_STICKY

        when (val action = intent?.action) {
            ACTION_START_VPN, ACTION_ALWAYS_ON_START -> {
                startVpn()
                returnCode = Service.START_REDELIVER_INTENT
            }
            ACTION_STOP_VPN -> {
                stopVpn()
            }
            else -> Timber.e("Unknown intent action: $action")
        }

        return returnCode
    }

    private fun startVpn() {
        Timber.i("Starting VPN")
        notifyVpnStart()

        tickerJob?.cancel()
        queues.clearAll()

        establishVpnInterface()
        schedulerReminderAlarm()
        hideReminderNotification()

        tunInterface?.let { vpnInterface ->
            executorService?.shutdownNow()
            val processors = listOf(
                tcpPacketProcessor,
                udpPacketProcessor,
                TunPacketReader(vpnInterface, queues),
                TunPacketWriter(vpnInterface, queues)
            )
            executorService = Executors.newFixedThreadPool(processors.size).also { executorService ->
                processors.forEach { executorService.submit(it) }
            }
        }
    }

    private fun updateNotificationForNewTrackerFound(trackersBlocked: List<VpnTrackerAndCompany>) {
        val trackerCompanies = trackersBlocked.groupBy { it.trackerCompany.trackerCompanyId }.size
        val notification = buildPersistentNotification(this, trackersBlocked, trackerCompanies)
        notificationManager.notify(FOREGROUND_VPN_SERVICE_ID, notification)
    }

    private fun hideReminderNotification() {
        notificationManager.cancel(VPN_REMINDER_NOTIFICATION_ID)
    }

    private fun schedulerReminderAlarm() {
        val receiver = ComponentName(this, VpnReminderReceiver::class.java)

        packageManager.setComponentEnabledSetting(
            receiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        val alarmIntent = Intent(this, VpnReminderReceiver::class.java).let { intent ->
            intent.action = ACTION_VPN_REMINDER
            PendingIntent.getBroadcast(this, 0, intent, 0)
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        alarmManager?.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(),
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            alarmIntent
        )
    }

    private fun establishVpnInterface() {
        tunInterface = Builder().run {
            addAddress("10.0.0.2", 32)
            addRoute("0.0.0.0", 0)
            setBlocking(true)
            setMtu(Short.MAX_VALUE.toInt())
            configureMeteredConnection()
            // addDnsServer("8.8.8.8")

            // Can either route all apps through VPN and exclude a few (better for prod), or exclude all apps and include a few (better for dev)
            val limitingToTestApps = false
            if (limitingToTestApps) {
                safelyAddAllowedApps(INCLUDED_APPS_FOR_TESTING)
                Timber.w("Limiting VPN to test apps only:\n${INCLUDED_APPS_FOR_TESTING.joinToString(separator = "\n") { it }}")
            } else {
                safelyAddDisallowedApps(EXCLUDED_APPS)
            }

            establish()
        }
    }

    private fun Builder.safelyAddAllowedApps(apps: List<String>) {
        for (app in apps) {
            try {
                addAllowedApplication(app)
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.w("Package name not found: %s", app)
            }
        }
    }

    private fun Builder.safelyAddDisallowedApps(apps: List<String>) {
        for (app in apps) {
            try {
                Timber.v("Excluding app from VPN: $app")
                addDisallowedApplication(app)
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.w("Package name not found: %s", app)
            }
        }
    }

    private fun stopVpn() {
        Timber.i("Stopping VPN")
        notifyVpnStopped()

        tickerJob?.cancel()
        queues.clearAll()
        executorService?.shutdownNow()
        udpPacketProcessor.stop()
        tcpPacketProcessor.stop()
        tunInterface?.close()
        tunInterface = null

        stopForeground(true)
        stopSelf()
    }

    private fun VpnService.Builder.configureMeteredConnection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(false)
        }
    }

    override fun onRevoke() {
        Timber.w("VPN onRevoke called")
        stopVpn()
        super.onRevoke()
    }

    private fun notifyVpnStart() {
        timeRunningTrackerJob?.cancel()
        timeRunningTrackerJob = launch(Dispatchers.Default) {
            // write initial time immediately once
            writeRunningTimeToDatabase(timeSinceLastRunningTimeSave())

            // update running time regularly with specified intervals
            while (isActive) {
                delay(1_000)
                writeRunningTimeToDatabase(timeSinceLastRunningTimeSave())
            }
        }

        startForeground(FOREGROUND_VPN_SERVICE_ID, buildPersistentNotification(applicationContext, emptyList(), 0))

        newTrackerObserverJob = launch {
            repository.getVpnTrackers({ dateOfPreviousMidnight() }).collectLatest {
                updateNotificationForNewTrackerFound(it)
            }
        }

    }

    private fun timeSinceLastRunningTimeSave(): Long {
        val timeNow = SystemClock.elapsedRealtime()
        return if (lastSavedTimestamp == 0L) 0 else timeNow - lastSavedTimestamp
    }

    private fun notifyVpnStopped() {
        timeRunningTrackerJob?.cancel()
        newTrackerObserverJob?.cancel()
        launch { writeRunningTimeToDatabase(timeSinceLastRunningTimeSave()) }
    }

    private fun writeRunningTimeToDatabase(runningTimeSinceLastSaveMillis: Long) {
        launch(Dispatchers.IO) {
            vpnDatabase.vpnRunningStatsDao().upsert(runningTimeSinceLastSaveMillis)
            lastSavedTimestamp = SystemClock.elapsedRealtime()
        }
    }

    companion object {

        const val ACTION_VPN_REMINDER = "com.duckduckgo.vpn.internaltesters.reminder"
        const val VPN_REMINDER_NOTIFICATION_ID = 999

        private fun serviceIntent(context: Context): Intent {
            return Intent(context, TrackerBlockingVpnService::class.java)
        }

        fun startIntent(context: Context): Intent {
            return serviceIntent(context).also {
                it.action = ACTION_START_VPN
            }
        }

        fun stopIntent(context: Context): Intent {
            return serviceIntent(context).also {
                it.action = ACTION_STOP_VPN
            }
        }

        // This method was deprecated in API level 26. As of Build.VERSION_CODES.O,
        // this method is no longer available to third party applications.
        // For backwards compatibility, it will still return the caller's own services.
        // So for us it's still valid because we don't need to know third party services, just ours.
        @Suppress("DEPRECATION")
        fun isServiceRunning(context: Context): Boolean {
            val manager: ActivityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (TrackerBlockingVpnService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }

        private const val ACTION_START_VPN = "ACTION_START_VPN"
        private const val ACTION_STOP_VPN = "ACTION_STOP_VPN"
        private const val ACTION_ALWAYS_ON_START = "android.net.VpnService"

        const val FOREGROUND_VPN_SERVICE_ID = 200
    }

    override fun createDatagramChannel(): DatagramChannel {
        return DatagramChannel.open().also { channel ->
            channel.configureBlocking(false)
            channel.socket().let { socket ->
                protect(socket)
                socket.broadcast = true
            }
        }
    }

    override fun createSocketChannel(): SocketChannel {
        return SocketChannel.open().also { channel ->
            channel.configureBlocking(false)
            protect(channel.socket())
        }
    }

    private val INCLUDED_APPS_FOR_TESTING = listOf(
        "com.duckduckgo.networkrequestor",
        "meteor.test.and.grade.internet.connection.speed",
        "org.zwanoo.android.speedtest",
        "com.netflix.Speedtest",
        "eu.vspeed.android",
        "net.fireprobe.android",
        "com.philips.lighting.hue2"
    )
}

interface NetworkChannelCreator {
    fun createDatagramChannel(): DatagramChannel
    fun createSocketChannel(): SocketChannel
}

class VpnQueues {
    val tcpDeviceToNetwork: BlockingDeque<Packet> = LinkedBlockingDeque()
    val udpDeviceToNetwork: BlockingQueue<Packet> = LinkedBlockingQueue()

    val networkToDevice: BlockingDeque<ByteBuffer> = LinkedBlockingDeque<ByteBuffer>()
    // val networkToDevice: ConcurrentLinkedQueue<ByteBuffer> = ConcurrentLinkedQueue()

    fun clearAll() {
        tcpDeviceToNetwork.clear()
        udpDeviceToNetwork.clear()
        networkToDevice.clear()
    }
}
