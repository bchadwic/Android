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

package com.duckduckgo.vpn.internal.feature.health

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.*
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.app.global.extensions.historicalExitReasonsByProcessName
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.mobile.android.vpn.R
import com.duckduckgo.mobile.android.vpn.databinding.ActivityVpnDiagnosticsBinding
import com.duckduckgo.mobile.android.vpn.health.*
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.ADD_TO_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_CONNECT_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_READ_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.SOCKET_CHANNEL_WRITE_EXCEPTION
import com.duckduckgo.mobile.android.vpn.health.SimpleEvent.Companion.TUN_READ
import com.duckduckgo.mobile.android.vpn.health.TracerPacketRegister.TracerSummary.Completed
import com.duckduckgo.mobile.android.vpn.model.TimePassed
import com.duckduckgo.mobile.android.vpn.service.VpnQueues
import com.duckduckgo.mobile.android.vpn.stats.AppTrackerBlockingStatsRepository
import dagger.android.AndroidInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.NumberFormat
import javax.inject.Inject

class VpnDiagnosticsActivity : DuckDuckGoActivity(), CoroutineScope by MainScope() {

    @Inject
    lateinit var tracerPacketBuilder: TracerPacketBuilder

    private lateinit var connectivityManager: ConnectivityManager

    private lateinit var binding: ActivityVpnDiagnosticsBinding

    @Inject
    lateinit var repository: AppTrackerBlockingStatsRepository

    @Inject
    lateinit var healthMetricCounter: HealthMetricCounter

    @Inject
    lateinit var tracerPacketRegister: TracerPacketRegister

    @Inject
    lateinit var vpnQueues: VpnQueues

    @Inject
    lateinit var appTPHealthMonitor: AppTPHealthMonitor

    private var timerUpdateJob: Job? = null

    private val numberFormatter = NumberFormat.getNumberInstance().also { it.maximumFractionDigits = 2 }

    private val automaticTracerInsertionJob = ConflatedJob()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpnDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))

        AndroidInjection.inject(this)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        stopTracing()
        configureEventHandlers()
        updateStatus()

        lifecycleScope.launch {
            appTPHealthMonitor.healthState.collect {
                Timber.i("Health is %s", it::class.java.simpleName)
            }
        }
    }

    private fun configureEventHandlers() {
        binding.clearTracersButton.setOnClickListener {
            tracerPacketRegister.deleteAll()
        }

        binding.insertTracerButton.setOnClickListener {
            insertTracer()
        }

        binding.toggleTracers.setOnClickListener {
            if(automaticTracerInsertionJob.isActive) {
                stopTracing()
            } else {
                startTracing()
            }
        }

        binding.simulateGoodHealth.setOnClickListener {
            appTPHealthMonitor.simulateHealthState(true)
        }

        binding.simulateBadHealth.setOnClickListener {
            appTPHealthMonitor.simulateHealthState(false)
        }

        binding.noSimulation.setOnClickListener {
            appTPHealthMonitor.simulateHealthState(null)
        }
    }

    private fun startTracing() {
        binding.toggleTracers.text = "Stop tracing"
        automaticTracerInsertionJob += lifecycleScope.launch {
            while(isActive) {
                insertTracer()
                delay(1_000)
            }
        }
    }

    private fun stopTracing() {
        automaticTracerInsertionJob.cancel()
        binding.toggleTracers.text = "Start tracing"
    }

    private fun insertTracer() {
        val packet = tracerPacketBuilder.build()
        tracerPacketRegister.logEvent(TracerEvent(packet.tracerId, TracedState.CREATED))
        tracerPacketRegister.logEvent(TracerEvent(packet.tracerId, TracedState.ADDED_TO_NETWORK_TO_DEVICE_QUEUE))
        vpnQueues.tcpDeviceToNetwork.offer(packet)
    }

    private fun updateStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val networkInfo = retrieveNetworkStatusInfo()
            val dnsInfo = retrieveDnsInfo()
            val addresses = retrieveIpAddressesInfo(networkInfo)
            val totalAppTrackers = retrieveAppTrackersBlockedInfo()
            val runningTimeFormatted = retrieveRunningTimeInfo()
            val appTrackersBlockedFormatted = generateTrackersBlocked(totalAppTrackers)
            val tracerInfo = retrieveTracerInfo()
            val healthMetricsInfo = retrieveHealthMetricsInfo()

            val healthMetricsFormatted = generateHealthMetricsStrings(healthMetricsInfo)

            withContext(Dispatchers.Main) {
                binding.networkAddresses.text = addresses
                binding.meteredConnectionStatus.text = getString(R.string.atp_MeteredConnection, networkInfo.metered.toString())
                binding.vpnStatus.text = getString(R.string.atp_ConnectionStatus, networkInfo.vpn.toString())
                binding.networkAvailable.text = getString(R.string.atp_NetworkAvailable, networkInfo.connectedToInternet.toString())
                binding.runningTime.text = runningTimeFormatted
                binding.appTrackersBlockedText.text = "App $appTrackersBlockedFormatted"
                binding.dnsServersText.text = getString(R.string.atp_DnsServers, dnsInfo)
                binding.tracerCompletionTimeMean.text =
                    String.format("Average trace time: %s ms", numberFormatter.format(tracerInfo.meanSuccessfulTime))
                binding.tracerNumberSuccessful.text = String.format("# successful traces: %d", tracerInfo.numberSuccessfulTraces)
                binding.tracerNumberFailed.text = String.format("# failed traces: %d", tracerInfo.numberFailedTraces)

                binding.healthMetrics.text = healthMetricsFormatted
            }
        }
    }

    private fun generateHealthMetricsStrings(healthMetricsInfo: HealthMetricsInfo): String {
        val healthMetricsStrings = mutableListOf<String>()

        healthMetricsStrings.add(
            String.format(
                "\n\ndevice-to-network queue writes: %d\ntun reads: %d\nrate: %s",
                healthMetricsInfo.writtenToDeviceToNetworkQueue,
                healthMetricsInfo.tunPacketReceived,
                calculatePercentage(healthMetricsInfo.writtenToDeviceToNetworkQueue, healthMetricsInfo.tunPacketReceived)
            )
        )

        healthMetricsStrings.add(
            String.format(
                "\n\ndevice-to-network queue writes: %d\nqueue reads: %d\nrate: %s",
                healthMetricsInfo.writtenToDeviceToNetworkQueue,
                healthMetricsInfo.removeFromDeviceToNetworkQueue,
                calculatePercentage(healthMetricsInfo.writtenToDeviceToNetworkQueue, healthMetricsInfo.removeFromDeviceToNetworkQueue)
            )
        )

        healthMetricsStrings.add(
            String.format(
                "\n\nSocket exceptions:\nRead: %d, Write: %d, Connect: %d",
                healthMetricsInfo.socketReadExceptions, healthMetricsInfo.socketWriteExceptions, healthMetricsInfo.socketConnectException
            )
        )

        val sb = StringBuilder()
        healthMetricsStrings.forEach {
            sb.append(it)
        }

        return sb.toString()
    }

    private fun calculatePercentage(numerator: Long, denominator: Long): String {
        if (denominator == 0L) return "0%"
        return String.format("%s%%", numberFormatter.format(numerator.toDouble() / denominator * 100))
    }

    private fun retrieveHealthMetricsInfo(): HealthMetricsInfo {
        val tunPacketReceived = healthMetricCounter.getStat(TUN_READ())
        val removeFromDeviceToNetworkQueue = healthMetricCounter.getStat(REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE())
        val writtenToDeviceToNetworkQueue = healthMetricCounter.getStat(ADD_TO_DEVICE_TO_NETWORK_QUEUE())
        val socketReadExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_READ_EXCEPTION())
        val socketWriteExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_WRITE_EXCEPTION())
        val socketConnectExceptions = healthMetricCounter.getStat(SOCKET_CHANNEL_CONNECT_EXCEPTION())
        return HealthMetricsInfo(
            tunPacketReceived = tunPacketReceived,
            writtenToDeviceToNetworkQueue = writtenToDeviceToNetworkQueue,
            removeFromDeviceToNetworkQueue = removeFromDeviceToNetworkQueue,
            socketReadExceptions = socketReadExceptions,
            socketWriteExceptions = socketWriteExceptions,
            socketConnectException = socketConnectExceptions
        )

    }

    private fun retrieveTracerInfo(): TracerInfo {
        val traces = tracerPacketRegister.getAllTraces()
        val completedTraces = traces.filterIsInstance<Completed>()

        val meanCompletedNs =
            if (completedTraces.isEmpty()) 0.0 else completedTraces.sumOf { it.timeToCompleteNanos }.toDouble() / completedTraces.size
        val meanCompletedMs = meanCompletedNs / 1_000_000
        return TracerInfo(completedTraces.size, traces.size - completedTraces.size, meanCompletedMs)
    }

    private fun retrieveHistoricalCrashInfo(): AppExitHistory {
        if (Build.VERSION.SDK_INT < 30) {
            return AppExitHistory()
        }

        val exitReasons = applicationContext.historicalExitReasonsByProcessName("com.duckduckgo.mobile.android.vpn:vpn", 10)
        return AppExitHistory(exitReasons)
    }

    private fun retrieveRestartsHistoryInfo(): AppExitHistory {
        return runBlocking {
            val restarts = withContext(Dispatchers.IO) {
                repository.getVpnRestartHistory()
                    .sortedByDescending { it.timestamp }
                    .map {
                        """
                        Restarted on ${it.formattedTimestamp}
                        App exit reason - ${it.reason}
                        """.trimIndent()
                    }
            }

            AppExitHistory(restarts)
        }
    }

    private suspend fun retrieveRunningTimeInfo() =
        generateTimeRunningMessage(repository.getRunningTimeMillis({ repository.noStartDate() }).firstOrNull() ?: 0L)

    private suspend fun retrieveAppTrackersBlockedInfo() = (repository.getVpnTrackers({ repository.noStartDate() }).firstOrNull() ?: emptyList()).size

    private fun retrieveIpAddressesInfo(networkInfo: NetworkInfo): String {
        return if (networkInfo.networks.isEmpty()) {
            "no addresses"
        } else {
            networkInfo.networks.joinToString("\n\n", transform = { "${it.type.type}:\n${it.address}" })
        }
    }

    private fun generateTimeRunningMessage(timeRunningMillis: Long): String {
        return if (timeRunningMillis == 0L) {
            getString(R.string.vpnNotRunYet)
        } else {
            return getString(R.string.vpnTimeRunning, TimePassed.fromMilliseconds(timeRunningMillis).format())
        }
    }

    private fun generateTrackersBlocked(totalTrackers: Int): String {
        return if (totalTrackers == 0) {
            applicationContext.getString(R.string.vpnTrackersNone)
        } else {
            return applicationContext.getString(R.string.vpnTrackersBlockedToday, totalTrackers)
        }
    }

    private fun retrieveNetworkStatusInfo(): NetworkInfo {
        val networks = getCurrentNetworkAddresses()
        val metered = connectivityManager.isActiveNetworkMetered
        val vpn = isVpnEnabled()
        val connectedToInternet = isConnectedToInternet()

        return NetworkInfo(networks, metered = metered, vpn = vpn, connectedToInternet = connectedToInternet)
    }

    private fun retrieveDnsInfo(): String {
        val dnsServerAddresses = mutableListOf<String>()

        runCatching {
            connectivityManager.allNetworks
                .filter { it.isConnected() }
                .mapNotNull { connectivityManager.getLinkProperties(it) }
                .map { it.dnsServers }
                .flatten()
                .forEach { dnsServerAddresses.add(it.hostAddress) }
        }

        return if (dnsServerAddresses.isEmpty()) return "none" else dnsServerAddresses.joinToString(", ") { it }
    }

    private fun android.net.Network.isConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.getNetworkCapabilities(this)?.hasCapability(NET_CAPABILITY_INTERNET) == true &&
                    connectivityManager.getNetworkCapabilities(this)?.hasCapability(NET_CAPABILITY_VALIDATED) == true
        } else {
            isConnectedLegacy(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun isConnectedLegacy(network: android.net.Network): Boolean {
        return connectivityManager.getNetworkInfo(network)?.isConnectedOrConnecting == true
    }

    private fun isConnectedToInternet(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isConnectedToInternetMarshmallowAndNewer()
        } else {
            isConnectedToInternetLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isConnectedToInternetMarshmallowAndNewer(): Boolean {

        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return false

        return capabilities.hasCapability(NET_CAPABILITY_VALIDATED)
    }

    @Suppress("DEPRECATION")
    private fun isConnectedToInternetLegacy(): Boolean {
        return connectivityManager.activeNetworkInfo?.isConnectedOrConnecting ?: false
    }

    private fun getCurrentNetworkAddresses(): List<Network> {
        val networks = mutableListOf<Network>()

        for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
            for (networkAddress in networkInterface.inetAddresses) {
                if (!networkAddress.isLoopbackAddress) {
                    networks.add(Network(address = networkAddress.hostAddress, type = addressType(address = networkAddress)))
                }
            }
        }

        return networks
    }

    private fun isVpnEnabled(): Boolean {
        return connectivityManager.allNetworks
            .mapNotNull { connectivityManager.getNetworkCapabilities(it) }
            .any { it.hasTransport(TRANSPORT_VPN) }
    }

    private fun addressType(address: InetAddress?): NetworkType {
        if (address is Inet6Address) return networkTypeV6
        if (address is Inet4Address) return networkTypeV4
        return networkTypeUnknown
    }

    override fun onStart() {
        super.onStart()

        timerUpdateJob?.cancel()
        timerUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateStatus()
                delay(1_000)
            }
        }

        appTPHealthMonitor.toggleNotifications(false)
    }

    override fun onStop() {
        super.onStop()
        timerUpdateJob?.cancel()

        appTPHealthMonitor.toggleNotifications(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.vpn_network_info_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                updateStatus()
                true
            }
            R.id.appExitHistory -> {
                val history = retrieveHistoricalCrashInfo()

                AlertDialog.Builder(this)
                    .setTitle(R.string.atp_AppExitsReasonsTitle)
                    .setMessage(history.toString())
                    .setPositiveButton("OK") { _, _ -> }
                    .setNeutralButton("Share") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).also {
                            it.type = "text/plain"
                            it.putExtra(Intent.EXTRA_TEXT, history.toString())
                            it.putExtra(Intent.EXTRA_SUBJECT, "Share VPN exit reasons")
                        }
                        startActivity(Intent.createChooser(intent, "Share"))
                    }
                    .show()
                true
            }
            R.id.vpnRestarts -> {
                val restarts = retrieveRestartsHistoryInfo()

                AlertDialog.Builder(this)
                    .setTitle(R.string.atp_AppRestartsTitle)
                    .setMessage(restarts.toString())
                    .setPositiveButton("OK") { _, _ -> }
                    .setNegativeButton("Clean") { _, _ ->
                        runBlocking(Dispatchers.IO) {
                            repository.deleteVpnRestartHistory()
                        }
                    }
                    .setNeutralButton("Share") { _, _ ->
                        val intent = Intent(Intent.ACTION_SEND).also {
                            it.type = "text/plain"
                            it.putExtra(Intent.EXTRA_TEXT, restarts.toString())
                            it.putExtra(Intent.EXTRA_SUBJECT, "Share VPN exit reasons")
                        }
                        startActivity(Intent.createChooser(intent, "Share"))
                    }
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, VpnDiagnosticsActivity::class.java)
        }
    }
}

data class AppExitHistory(
    val history: List<String> = emptyList()
) {
    override fun toString(): String {
        return if (history.isEmpty()) {
            "No exit history available"
        } else {
            history.joinToString(separator = "\n\n") { it }
        }
    }
}

data class TracerInfo(val numberSuccessfulTraces: Int, val numberFailedTraces: Int, val meanSuccessfulTime: Double)

data class HealthMetricsInfo(
    val tunPacketReceived: Long,
    val writtenToDeviceToNetworkQueue: Long,
    val removeFromDeviceToNetworkQueue: Long,
    val socketReadExceptions: Long,
    val socketWriteExceptions: Long,
    val socketConnectException: Long
)

data class NetworkInfo(
    val networks: List<Network>,
    val metered: Boolean,
    val vpn: Boolean,
    val connectedToInternet: Boolean
)

data class Network(
    val address: String,
    val type: NetworkType
)

val networkTypeV4 = NetworkType("IPv4")
val networkTypeV6 = NetworkType("IPv6")
val networkTypeUnknown = NetworkType("unknown")

inline class NetworkType(val type: String)
