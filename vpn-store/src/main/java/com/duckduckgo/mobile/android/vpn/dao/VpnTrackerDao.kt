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

package com.duckduckgo.mobile.android.vpn.dao

import androidx.room.*
import com.duckduckgo.mobile.android.vpn.model.BucketizedVpnTracker
import com.duckduckgo.mobile.android.vpn.model.VpnTracker
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnTrackerDao {

    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(tracker: VpnTracker)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(tracker: List<VpnTracker>)

    @Transaction
    @Query("SELECT * FROM vpn_tracker ORDER BY trackerId DESC LIMIT 1")
    fun getLatestTracker(): Flow<VpnTracker?>

    @Transaction
    @Query("SELECT * FROM vpn_tracker WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp DESC limit $MAX_NUMBER_OF_TRACKERS_IN_QUERY_RESULTS")
    fun getTrackersBetween(startTime: String, endTime: String): Flow<List<VpnTracker>>

    @Transaction
    @Query("SELECT * FROM vpn_tracker WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp DESC limit $MAX_NUMBER_OF_TRACKERS_IN_QUERY_RESULTS")
    fun getTrackersBetweenSync(startTime: String, endTime: String): List<VpnTracker>

    @Query("DELETE FROM vpn_tracker WHERE timestamp < :startTime")
    fun deleteOldDataUntil(startTime: String)

    @Transaction
    @Query("SELECT COUNT(*) FROM vpn_tracker WHERE timestamp >= :startTime AND timestamp < :endTime")
    fun getTrackersCountBetween(startTime: String, endTime: String): Flow<Int>

    @Transaction
    @Query("SELECT COUNT(DISTINCT packageId) FROM vpn_tracker WHERE timestamp >= :startTime AND timestamp < :endTime")
    fun getTrackingAppsCountBetween(startTime: String, endTime: String): Flow<Int>

    @Transaction
    @Query("SELECT strftime('%Y-%m-%d', timestamp) bucket, * FROM vpn_tracker WHERE timestamp >= :startTime order by timestamp DESC limit $MAX_NUMBER_OF_TRACKERS_IN_QUERY_RESULTS")
    fun getPagedTrackersSince(startTime: String): Flow<List<BucketizedVpnTracker>>

    companion object {
        private const val MAX_NUMBER_OF_TRACKERS_IN_QUERY_RESULTS = 10_000
    }
}
