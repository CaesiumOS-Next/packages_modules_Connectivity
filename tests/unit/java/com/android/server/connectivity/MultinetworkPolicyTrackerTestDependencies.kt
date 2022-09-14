package com.android.server.connectivity

import android.content.res.Resources
import android.net.ConnectivityResources
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import android.provider.DeviceConfig.OnPropertiesChangedListener
import com.android.internal.annotations.GuardedBy
import java.util.concurrent.Executor

private const val CONFIG_ACTIVELY_PREFER_BAD_WIFI = "actively_prefer_bad_wifi"
class MultinetworkPolicyTrackerTestDependencies(private val mResources: Resources) :
        MultinetworkPolicyTracker.Dependencies() {
    @GuardedBy("listeners")
    private var configActivelyPreferBadWifi = 0
    // TODO : move this to an actual fake device config object
    @GuardedBy("listeners")
    private val listeners = mutableListOf<Pair<Executor, OnPropertiesChangedListener>>()

    fun putConfigActivelyPreferBadWifi(value: Int) {
        synchronized(listeners) {
            configActivelyPreferBadWifi = value
            val p = DeviceConfig.Properties(NAMESPACE_CONNECTIVITY,
                    mapOf(CONFIG_ACTIVELY_PREFER_BAD_WIFI to value.toString()))
            listeners.forEach { (executor, listener) ->
                executor.execute { listener.onPropertiesChanged(p) }
            }
        }
    }

    override fun getConfigActivelyPreferBadWifi(): Int {
        return synchronized(listeners) { configActivelyPreferBadWifi }
    }

    override fun addOnDevicePropertiesChangedListener(
        e: Executor,
        listener: OnPropertiesChangedListener
    ) {
        synchronized(listeners) {
            listeners.add(e to listener)
        }
    }

    override fun getResourcesForActiveSubId(res: ConnectivityResources, id: Int): Resources =
            mResources
}
