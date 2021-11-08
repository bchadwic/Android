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

import androidx.room.*

@Database(
    version = 1,
    entities = [
        SimpleEvent::class,
    ],
)
abstract class HealthStatsDatabase : RoomDatabase() {
    abstract fun healthStatDao(): HealthStatDao
}

abstract class HealthStat(open val timestamp: Long = 0)

@Entity
data class SimpleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    override val timestamp: Long
) : HealthStat(timestamp) {

    companion object {
        fun build(type: String) = SimpleEvent(type = type, timestamp = System.currentTimeMillis())

        fun TUN_READ() = build("TUN_READ")
        fun ADD_TO_DEVICE_TO_NETWORK_QUEUE() = build("ADD_TO_DEVICE_TO_NETWORK_QUEUE")
        fun REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE() = build("REMOVE_FROM_DEVICE_TO_NETWORK_QUEUE")
    }
}

@Dao
interface HealthStatDao {

    @Insert
    fun insert(event: SimpleEvent)

//    @Insert
//    fun insert(event: TunRead)
//
//    @Insert
//    fun insert(event: WrittenToDeviceToNetworkQueue)
//
//    @Insert
//    fun insert(event: ReadFromDeviceToNetworkQueue)

    @Query("SELECT count(*) FROM SimpleEvent WHERE timestamp >= :timestamp AND type=:type")
    fun eventCount(type: String, timestamp: Long): Long

//    @Query("SELECT count(*) FROM TunRead WHERE timestamp >= :timestamp")
//    fun tunReadCount(timestamp: Long): Long
//
//    @Query("SELECT count(*) FROM WrittenToDeviceToNetworkQueue WHERE timestamp >= :timestamp")
//    fun writtenToDeviceToNetworkQueueCount(timestamp: Long): Long
//
//    @Query("SELECT count(*) FROM ReadFromDeviceToNetworkQueue WHERE timestamp >= :timestamp")
//    fun readFromDeviceToNetworkQueueCount(timestamp: Long): Long
}
