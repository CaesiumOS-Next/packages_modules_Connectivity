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

package com.android.testutils.com.android.testutils

import java.lang.reflect.Method
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit Rule that automatically sets feature flags based on `@FeatureFlag` annotations
 * present on test methods.
 *
 * This rule works by reading the annotation and dynamically
 * invoking a user-specified method before evaluation to handle the feature flag configuration.
 *
 * Usage:
 * ```kotlin
 * class MyTestClass {
 *   @get:Rule
 *   var setFeatureFlagsRule = SetFeatureFlagsRule(setFlagsMethod = "setFeatureFlag")
 *
 *   // ... test methods with @FeatureFlag annotations
 *
 *   static fun setFeatureFlag(name: String, enabled: Boolean) {
 *     // Custom handling code.
 *   }
 * }
 * ```
 *
 * Note:
 * 1. Ensure that the specified `setFlagsMethod` exists in your test class and has the
 *    signature `static fun setFeatureFlag(name: String, enabled: Boolean)`.
 * 2. This rule leverages reflection, so relevant methods need to have appropriate visibility.
 */
class SetFeatureFlagsRule(val setFlagsMethod: String) : TestRule {
    private lateinit var testInstance: Any // Store the instance.

    /**
     * This annotation marks a test method as requiring a specific feature flag to be configured.
     *
     * Use this on test methods to dynamically control feature flag states during testing.
     *
     * @param name The name of the feature flag.
     * @param enabled The desired state (true for enabled, false for disabled) of the feature flag.
     */
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class FeatureFlag(val name: String, val enabled: Boolean = true)

    /**
     * This method is the core of the rule, executed by the JUnit framework before each test method.
     *
     * It retrieves the test method's metadata.
     * If the `@FeatureFlag` annotation is found, it uses reflection to locate and invoke the
     *   user-specified `setFlagsMethod`, passing the feature flag's name and enabled state.
     */
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val testMethod = description.testClass.getMethod(description.methodName)
                val featureFlagAnnotations = testMethod.getAnnotationsByType(
                    FeatureFlag::class.java
                )

                for (featureFlagAnnotation in featureFlagAnnotations) {
                    val targetMethod: Method = try {
                        description.testClass.getMethod(
                            setFlagsMethod,
                            String::class.java,
                            Boolean::class.java
                        )
                    } catch (e: NoSuchMethodException) {
                        throw RuntimeException(
                            "Method '$setFlagsMethod' not found or incorrect signature",
                            e
                        )
                    }

                    // The evaluate() below will generate a new test instance. Hence, invoke a non-static
                    // method does not affect the test invocation.
                    targetMethod.invoke(
                        null,
                        featureFlagAnnotation.name,
                        featureFlagAnnotation.enabled
                    )
                }

                // Execute the test method, which includes methods annotated with
                // @Before, @Test and @After.
                base.evaluate()
            }
        }
    }
}
