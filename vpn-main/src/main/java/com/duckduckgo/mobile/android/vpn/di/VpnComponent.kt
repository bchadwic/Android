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

package com.duckduckgo.mobile.android.vpn.di

import com.duckduckgo.di.scopes.AppObjectGraph
import com.duckduckgo.di.scopes.VpnObjectGraph
import com.duckduckgo.mobile.android.vpn.service.TrackerBlockingVpnService
import com.duckduckgo.mobile.android.vpn.ui.report.DeviceShieldFragment
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Binds
import dagger.Module
import dagger.Subcomponent
import dagger.android.AndroidInjector
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dummy.ui.VpnDiagnosticsActivity
import dummy.ui.VpnControllerActivity

/**
 * This file contains the plumbing that was previously generated by dagger-android so that we can use Anvil and improve multi-module DI
 *
 * The file has three sections that replace @ContributesAndroidInjector to create dagger sub-components
 * for Android types, eg. Activity, Service, Fragment, etc.
 *
 * The following dagger-android code:
 *
 * ```
 *     @VpnScope
 *     @ContributesAndroidInjector
 *     abstract fun launchMyOtherVpnActivity(): MyOtherVpnActivity
 * ```
 * Will now require the following three steps
 * 1. Define the factory for MyActivity sub-component
 *```
 *      @VpnScope
 *      @MergeSubcomponent(
 *          scope = VpnObjectGraph::class
 *      )
 *      interface VpnControllerActivityComponent: AndroidInjector<MyOtherVpnActivity> {
 *          @Subcomponent.Factory
 *          interface Factory: AndroidInjector.Factory<MyOtherVpnActivity> {}
 *      }
 *```
 *
 * NOTE: What happens here? We are defining a Dagger component factory for our MyOtherVpnActivity component.
 * Dagger will internally generate the code for it
 *
 * 2. Make our App Component a factory of our new MyOtherVpnActivity sub-component so that it can create it
 *
 * ```
 *      @ContributesTo(AppObjectGraph::class)
 *      interface VpnComponentProvider {
 *          ...
 *          fun myOtherVpnActivityComponentFactory(): MyOtherVpnActivityComponent.Factory
 *          ...
 *      }
 *```
 *
 * NOTE: What happens here? The VpnComponentProvider interface will be implemented by our AppComponent
 *
 * 3. Bind the MyOtherVpnActivity component factory inside the App dagger component
 *
 * ```
 *      @Module
 *      @ContributesTo(AppObjectGraph::class)
 *      abstract class VpnBindingModule {
 *          ...
 *
 *          @Binds
 *          @IntoMap
 *          @ClassKey(MyOtherVpnActivity::class)
 *          abstract fun bindMyOtherVpnActivityComponentFactory(factory: MyOtherVpnActivityComponent.Factory): AndroidInjector.Factory<*>
 *
 *          ...
 *      }
 *```
 *
 * NOTE What happens here? We are binding (providing) the MyOtherVpnActivity component factory in the App dagger component so
 * that it can find it
 */

/* ======================================
 Step 1. Factory definition
 ====================================== */
@VpnScope
@MergeSubcomponent(
    scope = VpnObjectGraph::class
)
interface VpnControllerActivityComponent : AndroidInjector<VpnControllerActivity> {
    @Subcomponent.Factory
    interface Factory : AndroidInjector.Factory<VpnControllerActivity>
}

@VpnScope
@MergeSubcomponent(
    scope = VpnObjectGraph::class
)
interface DeviceShieldFragmentComponent : AndroidInjector<DeviceShieldFragment> {
    @Subcomponent.Factory
    interface Factory : AndroidInjector.Factory<DeviceShieldFragment>
}

@VpnScope
@MergeSubcomponent(
    scope = VpnObjectGraph::class
)
interface VpnDiagnosticsActivityComponent : AndroidInjector<VpnDiagnosticsActivity> {
    @Subcomponent.Factory
    interface Factory : AndroidInjector.Factory<VpnDiagnosticsActivity>
}

@VpnScope
@MergeSubcomponent(
    scope = VpnObjectGraph::class
)
interface TrackerBlockingVpnServiceComponent : AndroidInjector<TrackerBlockingVpnService> {
    @Subcomponent.Factory
    interface Factory : AndroidInjector.Factory<TrackerBlockingVpnService>
}

/* ============================================================================
 Step 2. Making the App dagger component a factory of our sub-components
 ============================================================================ */
@ContributesTo(AppObjectGraph::class)
interface VpnComponentProvider {
    fun vpnControllerActivityComponentFactory(): VpnControllerActivityComponent.Factory
    fun deviceShieldFragmentComponentFactory(): DeviceShieldFragmentComponent.Factory
    fun trackerBlockingVpnServiceComponentFactory(): TrackerBlockingVpnServiceComponent.Factory
    fun vpnDiagnosticsActivityComponentFactory(): VpnDiagnosticsActivityComponent.Factory
}

/* ============================================================================
 Step 3. Bind the factories in the App dagger component
 ============================================================================ */
@Module
@ContributesTo(AppObjectGraph::class)
abstract class VpnBindingModule {
    @Binds
    @IntoMap
    @ClassKey(VpnControllerActivity::class)
    abstract fun bindVpnControllerActivityComponentFactory(factory: VpnControllerActivityComponent.Factory): AndroidInjector.Factory<*>

    @Binds
    @IntoMap
    @ClassKey(DeviceShieldFragment::class)
    abstract fun bindDeviceShieldFragmentComponentFactory(factory: DeviceShieldFragmentComponent.Factory): AndroidInjector.Factory<*>

    @Binds
    @IntoMap
    @ClassKey(VpnDiagnosticsActivity::class)
    abstract fun bindVpnDiagnosticsActivityComponentFactory(factory: VpnDiagnosticsActivityComponent.Factory): AndroidInjector.Factory<*>

    @Binds
    @IntoMap
    @ClassKey(TrackerBlockingVpnService::class)
    abstract fun bindTrackerBlockingVpnServiceFactory(factory: TrackerBlockingVpnServiceComponent.Factory): AndroidInjector.Factory<*>
}
