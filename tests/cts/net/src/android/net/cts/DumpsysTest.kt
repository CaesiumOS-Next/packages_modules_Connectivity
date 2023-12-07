package android.net.cts

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.fail
import org.junit.Test

class DumpsysTest {
    @Test
    fun testDumpsysWhileSwitchingNetworks() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = InstrumentationRegistry.getInstrumentation().context
        val shouldStop = AtomicBoolean()
        val dumpsysThread = Thread {
            while (!shouldStop.get()) {
                ParcelFileDescriptor.AutoCloseInputStream(
                    uiAutomation.executeShellCommand("dumpsys connectivity")
                ).use {
                    it.readAllBytes()
                }
            }
        }
        dumpsysThread.start()

        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: fail("No ConnectivityManager")
        val request = NetworkRequest.Builder().build()
        val cbs = (1..10).map { NetworkCallback() }

        val timeoutMs = 5 * 60 * 1_000
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < start + timeoutMs) {
            cbs.forEach { cm.requestNetwork(request, it) }
            Thread.sleep(50)
            cbs.forEach { cm.unregisterNetworkCallback(it) }
        }
        shouldStop.set(true)
    }
}
