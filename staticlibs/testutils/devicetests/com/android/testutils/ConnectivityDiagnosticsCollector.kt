/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.device.collectors.BaseMetricListener
import android.device.collectors.DataRecord
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.time.ZonedDateTime
import kotlin.test.assertNull
import org.junit.runner.Description
import org.junit.runner.notification.Failure

private const val COLLECTOR_DIR = "run_listeners/connectivity_diagnostics"
private const val FILENAME_SUFFIX = "_conndiag.txt"

// Limit the number of dumped diagnostics to this number for a given test suite run to avoid
// collecting too much data when most methods are failing.
private const val MAX_DUMPS = 20

private val TAG = ConnectivityDiagnosticsCollector::class.simpleName

class ConnectivityDiagnosticsCollector : BaseMetricListener() {
    companion object {
        var instance: ConnectivityDiagnosticsCollector? = null
    }

    private val buffer = ByteArrayOutputStream()
    private val collectorDir: File by lazy {
        createAndEmptyDirectory(COLLECTOR_DIR)
    }
    private val outputFiles = mutableSetOf<String>()

    override fun onSetUp() {
        assertNull(instance, "ConnectivityDiagnosticsCollectors were set up multiple times")
        instance = this
        TryTestConfig.setDiagnosticsCollector { throwable ->
            collectTestFailureDiagnostics(throwable)
        }
    }

    override fun onCleanUp() {
        instance = null
    }

    override fun onTestFail(testData: DataRecord, description: Description, failure: Failure) {
        if (outputFiles.size >= MAX_DUMPS) return
        collectTestFailureDiagnostics(failure.exception)

        val baseFilename = "${description.className}#${description.methodName}_failure"
        flushBufferToFileMetric(testData, baseFilename)
    }

    override fun onTestEnd(testData: DataRecord, description: Description) {
        if (buffer.size() == 0) return
        if (outputFiles.size >= MAX_DUMPS) return

        // Flush any data that the test added to the buffer for dumping
        val baseFilename = "${description.className}#${description.methodName}_testdump"
        flushBufferToFileMetric(testData, baseFilename)
    }

    private fun flushBufferToFileMetric(testData: DataRecord, baseFilename: String) {
        var filename = baseFilename
        var i = 2
        while (outputFiles.contains(filename)) {
            filename = baseFilename + "_$i"
            i++
        }
        val outFile = File(collectorDir, filename + FILENAME_SUFFIX)
        outputFiles.add(filename)
        outFile.writeBytes(buffer.toByteArray())
        buffer.reset()
        val fileTag = "${ConnectivityDiagnosticsCollector::class.qualifiedName}_$filename"
        testData.addFileMetric(fileTag, outFile)
    }

    /**
     * Add connectivity diagnostics to the test data dump.
     *
     * <p>This collects a set of diagnostics that are relevant to connectivity test failures.
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectTestFailureDiagnostics(exceptionContext: Throwable? = null) {
        collectDumpsysConnectivity(exceptionContext)
    }

    /**
     * Add dumpsys connectivity to the test data dump.
     *
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectDumpsysConnectivity(exceptionContext: Throwable? = null) {
        Log.i(TAG, "Collecting dumpsys connectivity for test artifacts")
        PrintWriter(buffer).let {
            it.println("--- Dumpsys connectivity at ${ZonedDateTime.now()} ---")
            maybeWriteExceptionContext(it, exceptionContext)
            it.flush()
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "dumpsys connectivity --dump-priority HIGH")).use {
            it.copyTo(buffer)
        }
    }

    private fun maybeWriteExceptionContext(writer: PrintWriter, exceptionContext: Throwable?) {
        if (exceptionContext == null) return
        writer.println("At: ")
        exceptionContext.printStackTrace(writer)
    }
}