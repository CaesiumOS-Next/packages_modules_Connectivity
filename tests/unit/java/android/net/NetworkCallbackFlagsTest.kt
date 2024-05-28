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

package android.net

import android.net.ConnectivityManager.NetworkCallback
import android.net.ConnectivityManager.NetworkCallbackMethodsHolder
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
@RunWith(DevSdkIgnoreRunner::class)
class NetworkCallbackFlagsTest {
    @Test
    fun testNetworkCallbackMethods() {
        val calculatedMethods = getNetworkCallbackMethods()
        // Callees flags are only used for building and not part of NETWORK_CB_METHODS
        calculatedMethods.forEach { it.callees = 0 }
        assertEquals(
            calculatedMethods.toSet(),
            NetworkCallbackMethodsHolder.NETWORK_CB_METHODS.map {
                NetworkCallbackMethodBuilder(
                    it.mName,
                    it.mParameterTypes.toList(),
                    isFinal = false,
                    callees = 0,
                    callbacksCallingThisMethod = it.mCallbacksCallingThisMethod
                )
            }.toSet()
        )
    }

    data class NetworkCallbackMethodBuilder(
        val name: String,
        val parameterTypes: List<Class<*>>,
        val isFinal: Boolean,
        var callees: Int,
        var callbacksCallingThisMethod: Int
    )

    private fun getNetworkCallbackMethods(): List<NetworkCallbackMethodBuilder> {
        val parsedMethods = mutableListOf<NetworkCallbackMethodBuilder>()
        val methods = NetworkCallback::class.java.declaredMethods
        methods.forEach { method ->
            val cb = method.getAnnotation(
                NetworkCallback.FilteredCallback::class.java
            ) ?: return@forEach
            parsedMethods.add(
                NetworkCallbackMethodBuilder(
                    method.name,
                    method.parameterTypes.toList(),
                    Modifier.isFinal(method.modifiers),
                    cb.mayCall,
                    cb.value
                )
            )
        }

        // Propagate callbacksCallingThisMethod for transitive calls
        do {
            var hadChange = false
            parsedMethods.forEach { caller ->
                parsedMethods.forEach { callee ->
                    if (caller.callees and callee.callbacksCallingThisMethod != 0) {
                        // Callbacks that call the caller also cause calls to the callee. So the
                        // callee's bitmask should include the caller's method IDs.
                        val newValue =
                            caller.callbacksCallingThisMethod or callee.callbacksCallingThisMethod
                        hadChange = hadChange || callee.callbacksCallingThisMethod != newValue
                        callee.callbacksCallingThisMethod = newValue
                    }
                }
            }
        } while (hadChange)

        // Final methods may affect the flags for transitive calls, but cannot be overridden, so do
        // not need to be in the list (no overridden method in NetworkCallback will match them).
        return parsedMethods.filter { !it.isFinal }
    }

    @Test
    fun testMethodsAreAnnotated() {
        NetworkCallback::class.java.declaredMethods.forEach { method ->
            if (!Modifier.isPublic(method.modifiers) && !Modifier.isProtected(method.modifiers)) {
                return@forEach
            }
            val annotation = method.getAnnotation(NetworkCallback.FilteredCallback::class.java)
            assertNotNull(annotation, "$method is missing the @FilteredCallback annotation")
            val msg = "The FilteredCallback.value bitmask should have exactly only one bit set " +
                    "on $method"
            assertEquals(1, Integer.bitCount(annotation.value), msg)
        }
    }

    @Test
    fun testObviousCalleesAreInAnnotation() {
        NetworkCallback::class.java.declaredMethods.forEach { method ->
            val annotation = method.getAnnotation(NetworkCallback.FilteredCallback::class.java)
                ?: return@forEach
            val missingFlags = getObviousCallees(method) and annotation.mayCall.inv()
            val msg = "@FilteredCallback on $method is missing flags " +
                    "0x${Integer.toHexString(missingFlags)} in mayCall. There may be other " +
                    "calls that are not detected if they are done conditionally."
            assertEquals(0, missingFlags, msg)
        }
    }

    /**
     * Invoke the method with mocks, and return a bitmask of transitively called methods.
     *
     * This is not perfect as some callees could be called conditionally with only some arguments,
     * but it should catch obvious forgotten calls.
     */
    private fun getObviousCallees(method: Method): Int {
        // Create a mock only leaving the target method not mocked
        val mockCallback = mock(NetworkCallback::class.java)
        doCallRealMethod().`when`(mockCallback).let { mockObj ->
            val anyArgs = method.parameterTypes.map { any(it) }
            method.invoke(mockObj, *anyArgs.toTypedArray())
        }

        // Invoke the target method with mock parameters
        val mockParameters = method.parameterTypes.map { getMockFor(method, it) }
        method.invoke(mockCallback, *mockParameters.toTypedArray())

        // Aggregate callees
        val mockingDetails = mockingDetails(mockCallback)
        var callees = 0
        mockingDetails.invocations.forEach { inv ->
            if (inv.method == method) return@forEach
            val calleeFlag =
                inv.method.getAnnotation(NetworkCallback.FilteredCallback::class.java)?.value ?: 0
            callees = callees or calleeFlag
        }
        return callees
    }

    private fun getMockFor(method: Method, c: Class<*>): Any {
        if (!c.isPrimitive) {
            return mock(c)
        }
        return when (c) {
            Boolean::class.java -> false
            Int::class.java -> 0
            else -> fail("No mock set for parameter type $c used in $method")
        }
    }
}
