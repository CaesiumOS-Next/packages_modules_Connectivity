/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.net.cts

import android.provider.DeviceConfig
import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private val TAG = DeviceConfigRule::class.simpleName

class DeviceConfigRule(val retryCountIfConfigChanged: Int = 0) : TestRule {
    // Maps (namespace, key) -> value
    private val originalConfig = mutableMapOf<Pair<String, String>, String?>()
    private val usedConfig = mutableMapOf<Pair<String, String>, String?>()

    override fun apply(base: Statement, description: Description): Statement {
        return TestValidationUrlStatement(base, description)
    }

    private inner class TestValidationUrlStatement(
            private val base: Statement,
            private val description: Description
    ) : Statement() {
        override fun evaluate() {
            repeat (retryCountIfConfigChanged + 1) {
                try {
                    base.evaluate()
                    return
                } catch (e: Exception) {
                    usedConfig.forEach { (key, value) ->
                        if (DeviceConfig.getProperty(key.first, key.second) != value) {
                            Log.w(TAG, "Test failed with unexpected device config change, retrying")
                            return@repeat
                        }
                    }
                    throw e
                } finally {
                    originalConfig.forEach { (key, value) ->
                        DeviceConfig.setProperty(
                                key.first, key.second, value, false /* makeDefault */)
                    }
                }
            }
        }
    }

    fun setConfig(namespace: String, key: String, value: String?) {
        val originalValue = DeviceConfig.getProperty(namespace, key)
        val keyPair = Pair(namespace, key)
        originalConfig[keyPair] = originalValue
        DeviceConfig.setProperty(namespace, key, value, false /* makeDefault */)
    }
}