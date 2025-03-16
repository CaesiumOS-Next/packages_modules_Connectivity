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

package android.net.cts.util

import android.Manifest.permission.MODIFY_PHONE_STATE
import android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE
import android.content.pm.PackageManager
import android.os.ConditionVariable
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.CarrierPrivilegesCallback
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.android.compatibility.common.util.UiccUtil
import com.android.testutils.com.android.testutils.CarrierConfigRule
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import java.security.MessageDigest
import kotlin.test.assertTrue

private const val TAG = "CarrierPrivilegesUtils"
private const val TIMEOUT_MS = 5_000L

/**
 * A class to manage the lifecycle of an ethernet interface.
 *
 * This class encapsulates creating new tun/tap interfaces and registering them with ethernet
 * service.
 */
class CarrierPrivilegesUtils(private val carrierConfigRule: CarrierConfigRule) {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }

    private fun setHoldCarrierPrivilege(hold: Boolean, subId: Int) {
        fun getCertHash(): String {
            val pkgInfo = context.packageManager.getPackageInfo(
                context.opPackageName,
                PackageManager.GET_SIGNATURES
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val certHash = digest.digest(pkgInfo.signatures!![0]!!.toByteArray())
            return UiccUtil.bytesToHexString(certHash)!!
        }

        val tm = context.getSystemService(TelephonyManager::class.java)!!

        val cv = ConditionVariable()
        val cpb = PrivilegeWaiterCallback(cv)
        // The lambda below is capturing |cpb|, whose type inherits from a class that appeared in
        // T. This means the lambda will compile as a private method of this class taking a
        // PrivilegeWaiterCallback argument. As JUnit uses reflection to enumerate all methods
        // including private methods, this would fail with a link error when running on S-.
        // To solve this, make the lambda serializable, which causes the compiler to emit a
        // synthetic class instead of a synthetic method.
        tryTest @JvmSerializableLambda {
            val slotIndex = SubscriptionManager.getSlotIndex(subId)!!
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.registerCarrierPrivilegesCallback(slotIndex, { it.run() }, cpb)
            }
            // Wait for the callback to be registered
            assertTrue(cv.block(TIMEOUT_MS), "Can't register CarrierPrivilegesCallback")
            if (cpb.hasPrivilege == hold) {
                if (hold) {
                    Log.w(TAG, "Package ${context.opPackageName} already is privileged")
                } else {
                    Log.w(TAG, "Package ${context.opPackageName} already isn't privileged")
                }
                return@tryTest
            }
            if (hold) {
                carrierConfigRule.addConfigOverrides(subId, PersistableBundle().also {
                    it.putStringArray(CarrierConfigManager.KEY_CARRIER_CERTIFICATE_STRING_ARRAY,
                        arrayOf(getCertHash()))
                })
            } else {
                carrierConfigRule.cleanUpNow()
            }
        } cleanup @JvmSerializableLambda {
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.unregisterCarrierPrivilegesCallback(cpb)
            }
        }
    }

    fun acquireCarrierPrivilege(subId: Int) = setHoldCarrierPrivilege(true, subId)
    fun dropCarrierPrivilege(subId: Int) = setHoldCarrierPrivilege(false, subId)

    fun setCarrierServicePackageOverride(subId: Int, pkg: String?) {
        val tm = context.getSystemService(TelephonyManager::class.java)!!

        val cv = ConditionVariable()
        val cpb = CarrierServiceChangedWaiterCallback(cv)
        // The lambda below is capturing |cpb|, whose type inherits from a class that appeared in
        // T. This means the lambda will compile as a private method of this class taking a
        // PrivilegeWaiterCallback argument. As JUnit uses reflection to enumerate all methods
        // including private methods, this would fail with a link error when running on S-.
        // To solve this, make the lambda serializable, which causes the compiler to emit a
        // synthetic class instead of a synthetic method.
        tryTest @JvmSerializableLambda {
            val slotIndex = SubscriptionManager.getSlotIndex(subId)!!
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.registerCarrierPrivilegesCallback(slotIndex, { it.run() }, cpb)
            }
            // Wait for the callback to be registered
            assertTrue(cv.block(TIMEOUT_MS), "Can't register CarrierPrivilegesCallback")
            if (cpb.pkgName == pkg) {
                Log.w(TAG, "Carrier service package was already $pkg")
                return@tryTest
            }
            cv.close()
            runAsShell(MODIFY_PHONE_STATE) {
                if (null == pkg) {
                    // There is a bug is clear-carrier-service-package-override where not adding
                    // the -s argument will use the wrong slot index : b/299604822
                    runShellCommand("cmd phone clear-carrier-service-package-override" +
                            " -s $subId")
                } else {
                    // -s could set the subId, but this test works with the default subId.
                    runShellCommand("cmd phone set-carrier-service-package-override $pkg")
                }
            }
            assertTrue(cv.block(TIMEOUT_MS), "Can't modify carrier service package")
        } cleanup @JvmSerializableLambda {
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.unregisterCarrierPrivilegesCallback(cpb)
            }
        }
    }

    // Subclasses of CarrierPrivilegesCallback can't be inline, or they'll be compiled as
    // inner classes of the test class and will fail resolution on R as the test harness
    // uses reflection to list all methods and classes
    class PrivilegeWaiterCallback(private val cv: ConditionVariable) :
        CarrierPrivilegesCallback {
        var hasPrivilege = false
        override fun onCarrierPrivilegesChanged(p: MutableSet<String>, uids: MutableSet<Int>) {
            hasPrivilege = uids.contains(Process.myUid())
            cv.open()
        }
    }

    class CarrierServiceChangedWaiterCallback(private val cv: ConditionVariable) :
        CarrierPrivilegesCallback {
        var pkgName: String? = null
        override fun onCarrierPrivilegesChanged(p: MutableSet<String>, u: MutableSet<Int>) {}
        override fun onCarrierServiceChanged(pkgName: String?, uid: Int) {
            this.pkgName = pkgName
            cv.open()
        }
    }
}
