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

import androidx.lifecycle.LiveData
import androidx.room.*
import com.duckduckgo.mobile.android.vpn.model.VpnState

@Dao
interface VpnStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(state: VpnState)

    @Query("select * from vpn_state")
    fun get(): LiveData<VpnState>

    @Query("select * from vpn_state")
    fun getOneOff(): VpnState?
}
