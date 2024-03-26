package com.android.testutils.com.android.testutils

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.RecorderCallback.CallbackEntry
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.tryTest
import java.util.Collections
import kotlin.test.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A rule to file [NetworkCallback]s to request or watch networks.
 *
 * The callbacks filed in test methods are automatically unregistered when the method completes.
 */
class NetworkCallbackRule : TestRule {
    private val cm by lazy {
        InstrumentationRegistry.getInstrumentation().context
            .getSystemService(ConnectivityManager::class.java)
            ?: fail("ConnectivityManager not found")
    }
    private val cbToCleanup = Collections.synchronizedSet(mutableSetOf<NetworkCallback>())
    private var cellRequestCb: TestableNetworkCallback? = null

    /**
     * Convenience method to request a cell network, similarly to [request].
     *
     * The rule will keep tract of a single cell network request, which can be unrequested manually
     * using [unrequestCell].
     */
    fun requestCell(): Network {
        if (cellRequestCb != null) {
            fail("Cell network was already requested")
        }
        val cb = request(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build())
        cellRequestCb = cb
        return cb.expect<CallbackEntry.Available>(
            errorMsg = "Cell network not available. " +
                    "Please ensure the device has working mobile data."
        ).network
    }

    /**
     * Unrequest a cell network requested through [requestCell].
     */
    fun unrequestCell() {
        val cb = cellRequestCb ?: fail("Cell network was not requested")
        unregister(cb)
        cellRequestCb = null
    }

    /**
     * File a request for a Network.
     *
     * This will fail tests (throw) if the cell network cannot be obtained, or if it was already
     * requested.
     *
     * Tests may call [unregister] once they are done using the returned [Network], otherwise
     * it will be automatically unrequested after the test.
     */
    @JvmOverloads
    fun request(
        request: NetworkRequest,
        cb: TestableNetworkCallback = TestableNetworkCallback()
    ): TestableNetworkCallback {
        cm.requestNetwork(request, cb)
        cbToCleanup.add(cb)
        return cb
    }

    /**
     * File a callback for a NetworkRequest.
     *
     * This will fail tests (throw) if the cell network cannot be obtained, or if it was already
     * requested.
     *
     * Tests may call [unregister] once they are done using the returned [Network], otherwise
     * it will be automatically unrequested after the test.
     */
    @JvmOverloads
    fun registerCallback(
        request: NetworkRequest,
        cb: TestableNetworkCallback = TestableNetworkCallback()
    ): TestableNetworkCallback {
        cm.registerNetworkCallback(request, cb)
        cbToCleanup.add(cb)
        return cb
    }

    fun unregister(cb: NetworkCallback) {
        cm.unregisterNetworkCallback(cb)
        cbToCleanup.remove(cb)
    }

    override fun apply(base: Statement, description: Description): Statement {
        return RequestCellNetworkStatement(base, description)
    }

    private inner class RequestCellNetworkStatement(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            tryTest {
                base.evaluate()
            } cleanup {
                cbToCleanup.forEach { cm.unregisterNetworkCallback(it) }
                cbToCleanup.clear()
                cellRequestCb = null
            }
        }
    }
}