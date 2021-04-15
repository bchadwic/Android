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

package com.duckduckgo.mobile.android.vpn.trackers

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.duckduckgo.mobile.android.vpn.store.VpnDatabase
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TrackerListProviderTest {
    private lateinit var trackerListProvider: TrackerListProvider
    private lateinit var vpnDatabase: VpnDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        vpnDatabase = Room.inMemoryDatabaseBuilder(
            context,
            VpnDatabase::class.java
        ).allowMainThreadQueries().build().apply {
            VpnDatabase.prepopulateAppTrackerBlockingList(context, this)
        }

        val appTrackerRepository = RealAppTrackerRepository(context, Moshi.Builder().build(), vpnDatabase.vpnAppTrackerBlockingDao())
        trackerListProvider = RealTrackerListProvider(vpnDatabase.vpnPreferencesDao(), appTrackerRepository)
    }

    @Test
    fun whenFindTrackerInFullListMatchesThenReturnTracker() {
        trackerListProvider.setUseFullTrackerList(true)

        val tracker = trackerListProvider.findTracker("fls.doubleclick.net")

        assertNotNull(tracker)
    }

    @Test
    fun whenFindTrackerInLegacyListMatchesThenReturnTracker() = runBlocking {
        trackerListProvider.setUseFullTrackerList(false)

        val tracker = trackerListProvider.findTracker("fls.doubleclick.net")

        assertEquals("doubleclick.net", tracker?.hostname)
        assertEquals("Google", tracker?.owner?.displayName)
    }

    @Test
    fun whenFindTrackerInFullListDoesNotMatchThenReturnNull() {
        trackerListProvider.setUseFullTrackerList(true)

        val tracker = trackerListProvider.findTracker("tracker.llc")

        assertNull(tracker)
    }

    @Test
    fun whenFindTrackerInLegacyListDoesNotMatchThenReturnNull() {
        trackerListProvider.setUseFullTrackerList(false)

        val tracker = trackerListProvider.findTracker("tracker.llc")

        assertNull(tracker)
    }
}