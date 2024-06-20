/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.testutils

import android.content.Context
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE
import com.android.net.module.util.BitUtils.packBits
import com.android.net.module.util.LinkPropertiesUtils.CompareOrUpdateResult
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import java.lang.reflect.Modifier
import org.junit.runner.Description
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runner.manipulation.NoTestsRemainException
import org.junit.runner.manipulation.Sortable
import org.junit.runner.manipulation.Sorter
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runners.Parameterized
import org.mockito.Mockito

/**
 * A runner that can skip tests based on the development SDK as defined in [DevSdkIgnoreRule].
 *
 * Generally [DevSdkIgnoreRule] should be used for that purpose (using rules is preferable over
 * replacing the test runner), however JUnit runners inspect all methods in the test class before
 * processing test rules. This may cause issues if the test methods are referencing classes that do
 * not exist on the SDK of the device the test is run on.
 *
 * This runner inspects [IgnoreAfter] and [IgnoreUpTo] annotations on the test class, and will skip
 * the whole class if they do not match the development SDK as defined in [DevSdkIgnoreRule].
 * Otherwise, it will delegate to [AndroidJUnit4] to run the test as usual.
 *
 * This class automatically uses the Parameterized runner as its base runner when needed, so the
 * @Parameterized.Parameters annotation and its friends can be used in tests using this runner.
 *
 * Example usage:
 *
 *     @RunWith(DevSdkIgnoreRunner::class)
 *     @IgnoreUpTo(Build.VERSION_CODES.Q)
 *     class MyTestClass { ... }
 */
class DevSdkIgnoreRunner(private val klass: Class<*>) : Runner(), Filterable, Sortable {
    private val leakMonitorDesc = Description.createTestDescription(klass, "ThreadLeakMonitor")
    private val shouldThreadLeakFailTest = klass.isAnnotationPresent(MonitorThreadLeak::class.java)
    private val restoreDefaultNetworkDesc =
            Description.createTestDescription(klass, "RestoreDefaultNetwork")
    private val restoreDefaultNetwork = klass.isAnnotationPresent(RestoreDefaultNetwork::class.java)
    val ctx = ApplicationProvider.getApplicationContext<Context>()

    // Inference correctly infers Runner & Filterable & Sortable for |baseRunner|, but the
    // Java bytecode doesn't have a way to express this. Give this type a name by wrapping it.
    private class RunnerWrapper<T>(private val wrapped: T) :
            Runner(), Filterable by wrapped, Sortable by wrapped
            where T : Runner, T : Filterable, T : Sortable {
        override fun getDescription(): Description = wrapped.description
        override fun run(notifier: RunNotifier?) = wrapped.run(notifier)
    }

    @VisibleForTesting(visibility = PRIVATE)
    class DefaultNetworkRestoreMonitor(
            ctx: Context,
            private val notifier: RunNotifier
    ) {
        var firstFailure: Exception? = null
        var transports = 0L
        val cm = ctx.getSystemService(ConnectivityManager::class.java)!!
        val pm = ctx.packageManager
        val listener = object : RunListener() {
            override fun testFinished(description: Description) {
                // Only the first method that does not restore the default network should be blamed.
                if (firstFailure != null) {
                    return
                }
                val cb = TestableNetworkCallback()
                cm.registerDefaultNetworkCallback(cb)
                try {
                    cb.eventuallyExpect<CapabilitiesChanged>(timeoutMs = 3000) {
                        packBits(it.caps.transportTypes) == transports &&
                                it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
                    }
                } catch (e: AssertionError) {
                    firstFailure = IllegalStateException(description.methodName +
                            " does not restore the default network")
                } finally {
                    cm.unregisterNetworkCallback(cb)
                }
            }
        }

        fun init(connectUtil: ConnectUtil) {
            // Ensure Wi-Fi and cellular connection before running test to avoid starting test
            // with unexpected default network
            if (pm.hasSystemFeature(FEATURE_WIFI)) {
                connectUtil.ensureWifiValidated()
            }
            if (pm.hasSystemFeature(FEATURE_TELEPHONY)) {
                connectUtil.ensureCellularValidated()
            }
            // No need to wait here since the default network is already updated when
            // onCapabilitiesChanged is called with NET_CAPABILITY_VALIDATED
            val cap = cm.getNetworkCapabilities(cm.activeNetwork)
            if (cap == null) {
                firstFailure = IllegalStateException(
                        "Failed to get default network status before starting tests"
                )
            } else {
                transports = packBits(cap.transportTypes)
            }
            notifier.addListener(listener)
        }

        fun reportResultAndCleanUp(desc: Description) {
            notifier.fireTestStarted(desc)
            if (firstFailure != null) {
                notifier.fireTestFailure(
                        Failure(desc, firstFailure)
                )
            }
            notifier.fireTestFinished(desc)
            notifier.removeListener(listener)
        }
    }

    // Annotation for test classes to indicate the test runner should monitor thread leak.
    // TODO(b/307693729): Remove this annotation and monitor thread leak by default.
    annotation class MonitorThreadLeak

    // Annotation for test classes to indicate the test runner should verify the default network is
    // restored after each test.
    annotation class RestoreDefaultNetwork

    private val baseRunner: RunnerWrapper<*>? = klass.let {
        val ignoreAfter = it.getAnnotation(IgnoreAfter::class.java)
        val ignoreUpTo = it.getAnnotation(IgnoreUpTo::class.java)

        if (!isDevSdkInRange(ignoreUpTo, ignoreAfter)) {
            null
        } else if (it.hasParameterizedMethod()) {
            // Parameterized throws if there is no static method annotated with @Parameters, which
            // isn't too useful. Use it if there are, otherwise use its base AndroidJUnit4 runner.
            RunnerWrapper(Parameterized(klass))
        } else {
            RunnerWrapper(AndroidJUnit4(klass))
        }
    }

    private fun <T> Class<T>.hasParameterizedMethod(): Boolean = methods.any {
        Modifier.isStatic(it.modifiers) &&
                it.isAnnotationPresent(Parameterized.Parameters::class.java) }

    private fun checkThreadLeak(
            notifier: RunNotifier,
            threadCountsBeforeTest: Map<String, Int>
    ) {
        notifier.fireTestStarted(leakMonitorDesc)
        val threadCountsAfterTest = getAllThreadNameCounts()
        // TODO : move CompareOrUpdateResult to its own util instead of LinkProperties.
        val threadsDiff = CompareOrUpdateResult(
                threadCountsBeforeTest.entries,
                threadCountsAfterTest.entries
        ) { it.key }
        // Ignore removed threads, which typically are generated by previous tests.
        // Because this is in the threadsDiff.updated member, for sure there is a
        // corresponding key in threadCountsBeforeTest.
        val increasedThreads = threadsDiff.updated
                .filter { threadCountsBeforeTest[it.key]!! < it.value }
        if (threadsDiff.added.isNotEmpty() || increasedThreads.isNotEmpty()) {
            notifier.fireTestFailure(Failure(
                    leakMonitorDesc,
                    IllegalStateException("Unexpected thread changes: $threadsDiff")
            ))
        }
        // Clears up internal state of all inline mocks.
        // TODO: Call clearInlineMocks() at the end of each test.
        Mockito.framework().clearInlineMocks()
        notifier.fireTestFinished(leakMonitorDesc)
    }

    override fun run(notifier: RunNotifier) {
        if (baseRunner == null) {
            // Report a single, skipped placeholder test for this class, as the class is expected to
            // report results when run. In practice runners that apply the Filterable implementation
            // would see a NoTestsRemainException and not call the run method.
            notifier.fireTestIgnored(
                    Description.createTestDescription(klass, "skippedClassForDevSdkMismatch")
            )
            return
        }

        val networkRestoreMonitor = if (restoreDefaultNetwork) {
            DefaultNetworkRestoreMonitor(ctx, notifier).apply{
                init(ConnectUtil(ctx))
            }
        } else {
            null
        }
        val threadCountsBeforeTest = if (shouldThreadLeakFailTest) {
            // Dump threads as a baseline to monitor thread leaks.
            getAllThreadNameCounts()
        } else {
            null
        }

        baseRunner.run(notifier)

        if (threadCountsBeforeTest != null) {
            checkThreadLeak(notifier, threadCountsBeforeTest)
        }
        networkRestoreMonitor?.reportResultAndCleanUp(restoreDefaultNetworkDesc)
    }

    private fun getAllThreadNameCounts(): Map<String, Int> {
        // Get the counts of threads in the group per name.
        // Filter system thread groups.
        // Also ignore threads with 1 count, this effectively filtered out threads created by the
        // test runner or other system components. e.g. hwuiTask*, queued-work-looper,
        // SurfaceSyncGroupTimer, RenderThread, Time-limited test, etc.
        return Thread.getAllStackTraces().keys
                .filter { it.threadGroup?.name != "system" }
                .groupingBy { it.name }.eachCount()
                .filter { it.value != 1 }
    }

    override fun getDescription(): Description {
        if (baseRunner == null) {
            return Description.createSuiteDescription(klass)
        }

        return baseRunner.description.also {
            if (shouldThreadLeakFailTest) {
                it.addChild(leakMonitorDesc)
            }
            if (restoreDefaultNetwork) {
                it.addChild(restoreDefaultNetworkDesc)
            }
        }
    }

    /**
     * Get the test count before applying the [Filterable] implementation.
     */
    override fun testCount(): Int {
        // When ignoring the tests, a skipped placeholder test is reported, so test count is 1.
        if (baseRunner == null) return 1

        var testCount = baseRunner.testCount()
        if (shouldThreadLeakFailTest) {
            testCount += 1
        }
        if (restoreDefaultNetwork) {
            testCount += 1
        }
        return testCount
    }

    @Throws(NoTestsRemainException::class)
    override fun filter(filter: Filter?) {
        baseRunner?.filter(filter) ?: throw NoTestsRemainException()
    }

    override fun sort(sorter: Sorter?) {
        baseRunner?.sort(sorter)
    }
}
