/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.http.cts

import android.net.http.ConnectionMigrationOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionMigrationOptionsTest {

    @Test
    fun testConnectionMigrationOptions_defaultValues() {
        val options =
                ConnectionMigrationOptions.Builder().build()

        assertNull(options.allowNonDefaultNetworkUsage)
        assertNull(options.enableDefaultNetworkMigration)
        assertNull(options.enablePathDegradationMigration)
    }

    @Test
    fun testConnectionMigrationOptions_enableDefaultNetworkMigration_returnSetValue() {
        val options =
            ConnectionMigrationOptions.Builder().setEnableDefaultNetworkMigration(true).build()

        assertNotNull(options.enableDefaultNetworkMigration)
        assertTrue(options.enableDefaultNetworkMigration!!)
    }

    @Test
    fun testConnectionMigrationOptions_enablePathDegradationMigration_returnSetValue() {
        val options =
            ConnectionMigrationOptions.Builder().setEnablePathDegradationMigration(true).build()

        assertNotNull(options.enablePathDegradationMigration)
        assertTrue(options.enablePathDegradationMigration!!)
    }

    @Test
    fun testConnectionMigrationOptions_allowNonDefaultNetworkUsage_returnSetValue() {
        val options =
                ConnectionMigrationOptions.Builder().setAllowNonDefaultNetworkUsage(true).build()

        assertNotNull(options.allowNonDefaultNetworkUsage)
        assertTrue(options.allowNonDefaultNetworkUsage!!)
    }
}
