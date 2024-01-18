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

import android.os.Binder
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.LinkPropertiesUtils.CompareOrUpdateResult
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import java.io.BufferedReader
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import org.junit.runner.Description
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runner.manipulation.NoTestsRemainException
import org.junit.runner.manipulation.Sortable
import org.junit.runner.manipulation.Sorter
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import org.junit.runners.Parameterized

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
    private val TAG = DevSdkIgnoreRunner::class.simpleName
    private val threadLeakMonitorDesc = Description.createTestDescription(klass, "ThreadLeakMonitor")
    private val memoryLeakMonitorDesc = Description.createTestDescription(klass, "MemoryLeakMonitor")
    private val shouldThreadLeakFailTest = true
    private var threadCountsBeforeTest : Map<String, Int>? = null
    private val shouldMemoryLeakFailTest = true
    private var memoryUsageBeforeTest = 0L
    private val toleranceUsageBytes = 10_000_000L

    // Inference correctly infers Runner & Filterable & Sortable for |baseRunner|, but the
    // Java bytecode doesn't have a way to express this. Give this type a name by wrapping it.
    private class RunnerWrapper<T>(private val wrapped: T) :
            Runner(), Filterable by wrapped, Sortable by wrapped
            where T : Runner, T : Filterable, T : Sortable {
        override fun getDescription(): Description = wrapped.description
        override fun run(notifier: RunNotifier?) = wrapped.run(notifier)
    }

    // Annotation for test classes to indicate the test runner should monitor thread leak.
    // TODO(b/307693729): Remove this annotation and monitor thread leak by default.
    annotation class MonitorThreadLeak

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

    override fun run(notifier: RunNotifier) {
        if (baseRunner == null) {
            // Report a single, skipped placeholder test for this class, as the class is expected to
            // report results when run. In practice runners that apply the Filterable implementation
            // would see a NoTestsRemainException and not call the run method.
            notifier.fireTestIgnored(
                    Description.createTestDescription(klass, "skippedClassForDevSdkMismatch"))
            return
        }

        // Dump threads as a baseline to monitor thread leaks.
        if (shouldThreadLeakFailTest) {
            threadCountsBeforeTest = getAllThreadNameCounts()
        }

        if (shouldMemoryLeakFailTest) {
            waitForGc()
            memoryUsageBeforeTest = getCurrentMemoryUsage()
        }

        // Exclude memory killer to finish the tests.
        // if (klass.name.contains("ConnectivityServiceTest")) {
        //    return
        // }
        baseRunner.run(notifier)

        if (shouldMemoryLeakFailTest) {
            notifier.fireTestStarted(memoryLeakMonitorDesc)
            waitForGc()
            val memoryUsageAfterTest = getCurrentMemoryUsage()
            Log.d(TAG, "${klass.name} Usage " + (memoryUsageAfterTest - memoryUsageBeforeTest))
            if (memoryUsageAfterTest - memoryUsageBeforeTest > toleranceUsageBytes) {
                notifier.fireTestFailure(
                    Failure(
                        memoryLeakMonitorDesc,
                        IllegalStateException("Memory Usage " + (memoryUsageAfterTest - memoryUsageBeforeTest))
                    )
                )
                val myPid = Binder.getCallingPid()
                runShellCommand("am dumpheap $myPid /data/local/tmp/${klass::class.simpleName}.hprof")
            }
            notifier.fireTestFinished(memoryLeakMonitorDesc)
        }

        if (shouldThreadLeakFailTest) {
            notifier.fireTestStarted(threadLeakMonitorDesc)
            val threadCountsAfterTest = getAllThreadNameCounts()
            // TODO : move CompareOrUpdateResult to its own util instead of LinkProperties.
            val threadsDiff = CompareOrUpdateResult(
                threadCountsBeforeTest!!.entries,
                threadCountsAfterTest.entries
            ) { it.key }
            // Ignore removed threads, which typically are generated by previous tests.
            // Because this is in the threadsDiff.updated member, for sure there is a
            // corresponding key in threadCountsBeforeTest.
            val increasedThreads = threadsDiff.updated
                .filter { threadCountsBeforeTest!![it.key]!! < it.value }
            if (threadsDiff.added.isNotEmpty() || increasedThreads.isNotEmpty()) {
                notifier.fireTestFailure(
                    Failure(
                        threadLeakMonitorDesc,
                        IllegalStateException("Unexpected thread changes: $threadsDiff")
                    )
                )
            }
            notifier.fireTestFinished(threadLeakMonitorDesc)
        }
    }

    private fun runShellCommand(cmd: String): String {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .executeShellCommand(cmd).use { pfd ->
                AutoCloseInputStream(pfd).bufferedReader().use(BufferedReader::readText)
            }
    }

    private fun getCurrentMemoryUsage() =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun forceGC() {
        // First GC ensures that objects are collected for finalization, then second GC ensures
        // they're garbage-collected after being finalized.
        System.gc()
        System.runFinalization()
        System.gc()
    }

    private fun waitForGc() {
        val ref = WeakReference(Object())

        val attempts = 1000
        val waitIntervalMs: Long = 50
        var stabilizeCount = 0
        for (i in 0 until attempts) {
            val usageBeforeGc = getCurrentMemoryUsage()
            forceGC()
            val usageAfterGc = getCurrentMemoryUsage()
            if (ref.get() == null && usageAfterGc == usageBeforeGc) {
                stabilizeCount++
            } else {
                stabilizeCount = 0
            }
            if (ref.get() == null && stabilizeCount > 3) break
            Thread.sleep(waitIntervalMs)
        }
        if (ref.get() != null) Log.d(TAG, "Weak referenced object is not null after GC.")
        if (stabilizeCount <= 3) {
            Log.d(TAG, "Cannot stabilize usage: $stabilizeCount.")
        }
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
                it.addChild(threadLeakMonitorDesc)
            }
            if (shouldMemoryLeakFailTest) {
                it.addChild(memoryLeakMonitorDesc)
            }
        }
    }

    /**
     * Get the test count before applying the [Filterable] implementation.
     */
    override fun testCount(): Int {
        // When ignoring the tests, a skipped placeholder test is reported, so test count is 1.
        if (baseRunner == null) return 1

        return baseRunner.testCount() + if (shouldThreadLeakFailTest) 1 else 0 + if (shouldMemoryLeakFailTest) 1 else 0
    }

    @Throws(NoTestsRemainException::class)
    override fun filter(filter: Filter?) {
        baseRunner?.filter(filter) ?: throw NoTestsRemainException()
    }

    override fun sort(sorter: Sorter?) {
        baseRunner?.sort(sorter)
    }
}
