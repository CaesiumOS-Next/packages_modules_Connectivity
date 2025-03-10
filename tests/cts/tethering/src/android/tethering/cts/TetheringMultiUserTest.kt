/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.tethering.test

import android.app.ActivityManager.getCurrentUser
import android.os.UserHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bedstead.harrier.DeviceState
import com.android.bedstead.harrier.annotations.RequireRunOnPrimaryUser
import com.android.bedstead.harrier.annotations.RequireRunOnSecondaryUser
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
class TetheringMultiUserTest {
    companion object {
        @ClassRule
        @Rule
        @JvmField
        val deviceState = DeviceState()
    }

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.getContext()

    @RequireRunOnPrimaryUser
    @Test
    fun TestCreateContextAsUser_primaryUser() {
        doTestCreateContextAsUser()
    }

    @RequireRunOnSecondaryUser
    @Test
    fun TestCreateContextAsUser_secondaryUser() {
        doTestCreateContextAsUser()
    }

    private fun doTestCreateContextAsUser() {
        val packageNames = listOf("com.android.networkstack.tethering",
                "com.android.networkstack")
        val currentUserId: Int = getCurrentUser()
        val currentUser = UserHandle.of(currentUserId)
        for (packageName in packageNames) {
            context.createPackageContextAsUser(packageName, 0 /* flags */, currentUser)
        }
    }
}