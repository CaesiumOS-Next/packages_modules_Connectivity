package com.android.server.connectivity

import android.Manifest.permission.READ_DEVICE_CONFIG
import android.content.res.Resources
import android.net.ConnectivityResources
import android.provider.DeviceConfig
import com.android.testutils.runAsShell
import java.util.concurrent.Executor

class MultinetworkPolicyTrackerTestDependencies(private val mResources: Resources) :
        MultinetworkPolicyTracker.Dependencies() {
    override fun getDeviceConfigPropertyInt(name: String, defaultVal: Int): Int {
        return runAsShell(READ_DEVICE_CONFIG) { super.getDeviceConfigPropertyInt(name, defaultVal) }
    }

    override fun addOnDevicePropertiesChangedListener(
        e: Executor,
        listener: DeviceConfig.OnPropertiesChangedListener
    ) {
        runAsShell(READ_DEVICE_CONFIG) { super.addOnDevicePropertiesChangedListener(e, listener) }
    }

    override fun getResourcesForActiveSubId(res: ConnectivityResources, id: Int): Resources =
            mResources
}
