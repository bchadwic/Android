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

package dummy.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.mobile.android.vpn.R
import com.duckduckgo.mobile.android.vpn.model.TimePassed
import com.duckduckgo.mobile.android.vpn.stats.AppTrackerBlockingStatsRepository
import dagger.android.AndroidInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

class NetworkInfoActivity : AppCompatActivity(R.layout.activity_network_info), CoroutineScope by MainScope() {

    private lateinit var networkAddresses: TextView
    private lateinit var meteredConnectionText: TextView
    private lateinit var vpnActiveText: TextView
    private lateinit var networkAvailable: TextView
    private lateinit var runningTime: TextView
    private lateinit var trackersTextView: TextView

    private lateinit var connectivityManager: ConnectivityManager

    @Inject
    lateinit var repository: AppTrackerBlockingStatsRepository

    private var timerUpdateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))

        AndroidInjection.inject(this)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setViewReferences()
        updateNetworkStatus()
    }

    private fun updateNetworkStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val networkInfo = retrieveNetworkInfo()
            val addresses = if (networkInfo.networks.isEmpty()) {
                "no addresses"
            } else {
                networkInfo.networks.joinToString("\n\n", transform = { "${it.type.type}:\n${it.address}" })
            }

            val totalTrackers = (repository.getVpnTrackers({ repository.noStartDate() }).firstOrNull() ?: emptyList()).size
            val runningTimeFormatted = generateTimeRunningMessage(repository.getRunningTimeMillis({ repository.noStartDate() }).firstOrNull() ?: 0L)
            val trackersBlockedFormatted = generateTrackersBlocked(totalTrackers)

            withContext(Dispatchers.Main) {
                networkAddresses.text = addresses
                meteredConnectionText.text = getString(R.string.meteredConnection, networkInfo.metered.toString())
                vpnActiveText.text = getString(R.string.vpnConnectionStatus, networkInfo.vpn.toString())
                networkAvailable.text = getString(R.string.networkAvailable, networkInfo.connectedToInternet.toString())
                runningTime.text = runningTimeFormatted
                trackersTextView.text = trackersBlockedFormatted
            }
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
            return applicationContext.getString(R.string.vpnTrackersBlocked, totalTrackers)
        }
    }

    private fun retrieveNetworkInfo(): NetworkInfo {
        val networks = getCurrentNetworkAddresses()
        val metered = connectivityManager.isActiveNetworkMetered
        val vpn = isVpnEnabled()

        val connectedToInternet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isConnectedToInternetMarshmallowAndNewer()
        } else {
            isConnectedToInternetLegacy()
        }

        return NetworkInfo(networks, metered = metered, vpn = vpn, connectedToInternet = connectedToInternet)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isConnectedToInternetMarshmallowAndNewer(): Boolean {

        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
                updateNetworkStatus()
                delay(1_000)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        timerUpdateJob?.cancel()
    }

    private fun setViewReferences() {
        networkAddresses = findViewById(R.id.networkAddresses)
        meteredConnectionText = findViewById(R.id.meteredConnectionStatus)
        vpnActiveText = findViewById(R.id.vpnStatus)
        networkAvailable = findViewById(R.id.networkAvailable)
        runningTime = findViewById(R.id.runningTime)
        trackersTextView = findViewById(R.id.trackersBlockedText)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.vpn_network_info_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                updateNetworkStatus()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, NetworkInfoActivity::class.java)
        }
    }
}

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
