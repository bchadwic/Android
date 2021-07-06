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

package com.duckduckgo.mobile.android.vpn.ui.tracker_activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.app.global.ViewModelFactory
import com.duckduckgo.mobile.android.ui.view.gone
import com.duckduckgo.mobile.android.ui.view.show
import com.duckduckgo.mobile.android.vpn.BuildConfig
import com.duckduckgo.mobile.android.vpn.R
import com.duckduckgo.mobile.android.vpn.apps.ui.DeviceShieldExclusionListActivity
import com.duckduckgo.mobile.android.vpn.pixels.DeviceShieldPixels
import com.duckduckgo.mobile.android.vpn.service.TrackerBlockingVpnService
import com.duckduckgo.mobile.android.vpn.ui.onboarding.DeviceShieldFAQActivity
import com.duckduckgo.mobile.android.vpn.ui.report.DeviceShieldAppTrackersInfo
import dagger.android.AndroidInjection
import dummy.quietlySetIsChecked
import dummy.ui.VpnControllerActivity
import dummy.ui.VpnDiagnosticsActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DeviceShieldTrackerActivity :
    AppCompatActivity(R.layout.activity_device_shield_activity),
    DeviceShieldActivityFeedFragment.DeviceShieldActivityFeedListener {

    @Inject
    lateinit var viewModelFactory: ViewModelFactory

    @Inject
    lateinit var deviceShieldPixels: DeviceShieldPixels

    private lateinit var trackerBlockedCountView: PastWeekTrackerActivityContentView
    private lateinit var trackingAppsCountView: PastWeekTrackerActivityContentView
    private lateinit var ctaTrackerFaq: View
    private lateinit var deviceShieldEnabledLabel: TextView
    private lateinit var deviceShieldDisabledLabel: TextView
    private lateinit var deviceShieldSwitch: SwitchCompat
    private lateinit var ctaShowAll: View

    private val feedConfig = DeviceShieldActivityFeedFragment.ActivityFeedConfig(
        maxRows = 6,
        timeWindow = 5,
        timeWindowUnits = TimeUnit.DAYS,
        showTimeWindowHeadings = false,
        minRowsForAllActivity = 6
    )

    private val deviceShieldToggleListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        viewModel.onDeviceShieldSettingChanged(isChecked)
    }

    private val viewModel: DeviceShieldTrackerActivityViewModel by bindViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidInjection.inject(this)

        bindViews()
        showDeviceShieldActivity()
        observeViewModel()

        deviceShieldPixels.didShowPrivacyReport()
    }

    private fun bindViews() {
        trackerBlockedCountView = findViewById(R.id.trackers_blocked_count)
        trackingAppsCountView = findViewById(R.id.tracking_apps_count)
        ctaTrackerFaq = findViewById(R.id.cta_tracker_faq)
        deviceShieldEnabledLabel = findViewById(R.id.deviceShieldTrackerLabelEnabled)
        deviceShieldDisabledLabel = findViewById(R.id.deviceShieldTrackerLabelDisabled)
        ctaShowAll = findViewById(R.id.cta_show_all)

        findViewById<Button>(R.id.cta_tracker_faq).setOnClickListener {
            viewModel.onViewEvent(DeviceShieldTrackerActivityViewModel.ViewEvent.LaunchDeviceShieldFAQ)
        }

        findViewById<Button>(R.id.cta_excluded_apps).setOnClickListener {
            viewModel.onViewEvent(DeviceShieldTrackerActivityViewModel.ViewEvent.LaunchExcludedApps)
        }

        findViewById<Button>(R.id.cta_beta_instructions).setOnClickListener {
            viewModel.onViewEvent(DeviceShieldTrackerActivityViewModel.ViewEvent.LaunchBetaInstructions)
        }

        findViewById<Button>(R.id.cta_what_are_app_trackers).setOnClickListener {
            viewModel.onViewEvent(DeviceShieldTrackerActivityViewModel.ViewEvent.LaunchAppTrackersFAQ)
        }

        ctaShowAll.setOnClickListener {
            viewModel.onViewEvent(DeviceShieldTrackerActivityViewModel.ViewEvent.LaunchMostRecentActivity)
        }
    }

    override fun onTrackerListShowed(totalTrackers: Int) {
        if (totalTrackers >= feedConfig.minRowsForAllActivity) {
            ctaShowAll.show()
        } else {
            ctaShowAll.gone()
        }
    }

    private fun showDeviceShieldActivity() {
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.activity_list,
                DeviceShieldActivityFeedFragment.newInstance(feedConfig)
            )
            .commitNow()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.getBlockedTrackersCount()
                .combine(viewModel.getTrackingAppsCount()) { trackers, apps ->
                    DeviceShieldTrackerActivityViewModel.TrackerCountInfo(trackers, apps)
                }
                .combine(viewModel.vpnRunningState) { trackerCountInfo, runningState ->
                    DeviceShieldTrackerActivityViewModel.TrackerActivityViewState(trackerCountInfo, runningState)
                }
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { renderViewState(it) }

        }

        viewModel.commands()
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { processCommand(it) }
            .launchIn(lifecycleScope)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        intent.getParcelableExtra<ResultReceiver>(RESULT_RECEIVER_EXTRA)?.let {
            it.send(ON_LAUNCHED_CALLED_SUCCESS, null)
        }
    }

    override fun onBackPressed() {
        onSupportNavigateUp()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun processCommand(it: DeviceShieldTrackerActivityViewModel.Command?) {
        when (it) {
            is DeviceShieldTrackerActivityViewModel.Command.StartDeviceShield -> startDeviceShield()
            is DeviceShieldTrackerActivityViewModel.Command.StopDeviceShield -> stopDeviceShield()
            is DeviceShieldTrackerActivityViewModel.Command.LaunchAppTrackersFAQ -> launchAppTrackersFAQ()
            is DeviceShieldTrackerActivityViewModel.Command.LaunchBetaInstructions -> launchBetaInstructions()
            is DeviceShieldTrackerActivityViewModel.Command.LaunchDeviceShieldFAQ -> launchDeviceShieldFAQ()
            is DeviceShieldTrackerActivityViewModel.Command.LaunchExcludedApps -> launchExcludedApps()
            is DeviceShieldTrackerActivityViewModel.Command.LaunchMostRecentActivity -> launchMostRecentActivity()
        }
    }

    private fun launchExcludedApps() {
        startActivity(DeviceShieldExclusionListActivity.intent(this))
    }

    private fun launchDeviceShieldFAQ() {
        startActivity(DeviceShieldFAQActivity.intent(this))
    }

    private fun launchBetaInstructions() {
        startActivity(DeviceShieldFAQActivity.intent(this))
    }

    private fun launchAppTrackersFAQ() {
        startActivity(DeviceShieldAppTrackersInfo.intent(this))
    }

    private fun launchMostRecentActivity() {
        startActivity(DeviceShieldMostRecentActivity.intent(this))
    }

    private fun startDeviceShield() {
        TrackerBlockingVpnService.startService(this)
    }

    private fun stopDeviceShield() {
        TrackerBlockingVpnService.stopService(this)
    }

    private fun renderViewState(state: DeviceShieldTrackerActivityViewModel.TrackerActivityViewState) {
        // is there a better way to do this?
        if (::deviceShieldSwitch.isInitialized) {
            deviceShieldSwitch.quietlySetIsChecked(state.runningState.isRunning, deviceShieldToggleListener)
        }

        updateCounts(state.trackerCountInfo)
        updateRunningState(state.runningState)
    }

    private fun updateCounts(trackerCountInfo: DeviceShieldTrackerActivityViewModel.TrackerCountInfo) {
        trackerBlockedCountView.count = trackerCountInfo.stringTrackerCount()
        trackerBlockedCountView.footer =
            resources.getQuantityString(R.plurals.deviceShieldActivityPastWeekTrackerCount, trackerCountInfo.trackers.value)

        trackingAppsCountView.count = trackerCountInfo.stringAppsCount()
        trackingAppsCountView.footer = resources.getQuantityString(R.plurals.deviceShieldActivityPastWeekAppCount, trackerCountInfo.apps.value)
    }

    private fun updateRunningState(state: RunningState) {
        if (state.isRunning) {
            deviceShieldDisabledLabel.gone()
            deviceShieldEnabledLabel.show()
            deviceShieldEnabledLabel.apply {
                text = addClickableLink(getText(R.string.deviceShieldActivityEnabledLabel))
                movementMethod = LinkMovementMethod.getInstance()
            }
        } else {
            deviceShieldEnabledLabel.gone()
            deviceShieldDisabledLabel.show()
            deviceShieldDisabledLabel.apply {
                text = addClickableLink(getText(R.string.deviceShieldActivityDisabledLabel))
                movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    private fun addClickableLink(text: CharSequence): SpannableString {
        val fullText = text as SpannedString
        val spannableString = SpannableString(fullText)
        val annotations = fullText.getSpans(0, fullText.length, Annotation::class.java)
        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                launchFeedback()
            }
        }

        annotations?.find { it.value == "report_issues_link" }?.let {
            spannableString.apply {
                setSpan(
                    clickableSpan,
                    fullText.getSpanStart(it),
                    fullText.getSpanEnd(it),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    UnderlineSpan(),
                    fullText.getSpanStart(it),
                    fullText.getSpanEnd(it),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    ForegroundColorSpan(
                        ContextCompat.getColor(baseContext, R.color.almostBlackDark)
                    ),
                    fullText.getSpanStart(it),
                    fullText.getSpanEnd(it),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannableString
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_device_tracker_activity, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.debugLogging).isChecked = viewModel.getDebugLoggingPreference()
        menu.findItem(R.id.customDnsServer)?.let {
            it.isChecked = viewModel.isCustomDnsServerSet()
            it.isEnabled = !TrackerBlockingVpnService.isServiceRunning(this)
        }
        menu.findItem(R.id.diagnosticsScreen).isVisible = BuildConfig.DEBUG
        menu.findItem(R.id.dataScreen).isVisible = BuildConfig.DEBUG
        menu.findItem(R.id.debugLogging).isVisible = BuildConfig.DEBUG
        menu.findItem(R.id.customDnsServer).isVisible = BuildConfig.DEBUG

        val switchMenuItem = menu.findItem(R.id.deviceShieldSwitch)
        deviceShieldSwitch = switchMenuItem?.actionView as SwitchCompat

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.deviceShieldSwitch -> {
                startVpnIfAllowed(); true
            }
            R.id.dataScreen -> {
                startActivity(VpnControllerActivity.intent(this)); true
            }
            R.id.diagnosticsScreen -> {
                startActivity(VpnDiagnosticsActivity.intent(this)); true
            }
            R.id.debugLogging -> {
                val enabled = !item.isChecked
                viewModel.useDebugLogging(enabled)
                reconfigureTimber(enabled)
                true
            }
            R.id.customDnsServer -> {
                val enabled = !item.isChecked
                viewModel.useCustomDnsServer(enabled)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startVpnIfAllowed() {
    }

    private fun launchFeedback() {
        startActivity(Intent(Intent.ACTION_VIEW, VpnControllerActivity.FEEDBACK_URL))
    }

    private fun reconfigureTimber(debugLoggingEnabled: Boolean) {
        if (debugLoggingEnabled) {
            Timber.uprootAll()
            Timber.plant(Timber.DebugTree())
            Timber.w("Logging Started")
        } else {
            Timber.w("Logging Ended")
            Timber.uprootAll()
        }
    }

    companion object {
        private const val RESULT_RECEIVER_EXTRA = "RESULT_RECEIVER_EXTRA"
        private const val ON_LAUNCHED_CALLED_SUCCESS = 0

        fun intent(context: Context, onLaunchCallback: ResultReceiver? = null): Intent {
            return Intent(context, DeviceShieldTrackerActivity::class.java).apply {
                putExtra(RESULT_RECEIVER_EXTRA, onLaunchCallback)
            }
        }
    }

    private inline fun <reified V : ViewModel> bindViewModel() = lazy { ViewModelProvider(this, viewModelFactory).get(V::class.java) }

}
