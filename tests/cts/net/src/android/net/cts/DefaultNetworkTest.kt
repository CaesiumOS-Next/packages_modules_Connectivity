package android.net.cts

import android.Manifest.permission.NETWORK_SETTINGS
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.cts.util.CtsNetUtils
import androidx.test.InstrumentationRegistry
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

private const val CELL_CONNECT_TIMEOUT_MS = 30_000L

@RunWith(DevSdkIgnoreRunner::class)
class DefaultNetworkTest {
    val context = InstrumentationRegistry.getInstrumentation().getContext()
    val ctsUtils = CtsNetUtils(context)
    val cm = context.getSystemService(ConnectivityManager::class.java)!!

    @Test
    fun testCellAloneBecomesDefault() {
        assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
        val cb = TestableNetworkCallback(timeoutMs = CELL_CONNECT_TIMEOUT_MS)
        tryTest {
            // Turn airplane mode on and off, in case cell was connected for some process
            // before this tests run
            runAsShell(NETWORK_SETTINGS) {
                CtsNetUtils.setAndVerifyAirplaneMode(context, cm, true)
                CtsNetUtils.setAndVerifyAirplaneMode(context, cm, false)
            }
            // Disable wifi
            ctsUtils.disableWifi()

            cm.registerDefaultNetworkCallback(cb)
            cb.expect<Available>()
            cb.expect<CapabilitiesChanged> {
                it.caps.hasTransport(TRANSPORT_CELLULAR) || it.caps.hasTransport(TRANSPORT_ETHERNET)
            }
        } cleanupStep {
            cm.unregisterNetworkCallback(cb)
        } cleanup {
            ctsUtils.ensureWifiConnected()
        }
    }
}
