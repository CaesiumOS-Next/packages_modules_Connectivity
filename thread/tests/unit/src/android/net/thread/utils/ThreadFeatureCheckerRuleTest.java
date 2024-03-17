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

package android.net.thread.utils;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.thread.IThreadNetworkManager;
import android.net.thread.ThreadNetworkManager;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresIpv6MulticastRouting;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.os.SystemProperties;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.mockito.MockitoAnnotations;

import java.lang.annotation.Annotation;

/** Unit tests for {@link ThreadFeatureCheckerRule}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ThreadFeatureCheckerRuleTest {
    private ThreadFeatureCheckerRule mCheckerRule;

    private EmptyStatement mBaseStatement;

    private Context mContext;
    private ThreadNetworkManager mManager;

    /**
     * A {@link Statement} implementation which does nothing but records the text execution history.
     */
    private static final class EmptyStatement extends Statement {
        private boolean mIsTestExecuted = false;

        @Override
        public void evaluate() throws Throwable {
            mIsTestExecuted = true;
        }

        public boolean isTestExecuted() {
            return mIsTestExecuted;
        }
    }

    /** A test class with only method annotations. */
    private static final class TestMethodAnnotations {
        @RequiresThreadFeature
        public void testRequiresThreadFeature() {}

        @RequiresIpv6MulticastRouting
        public void testRequiresIpv6MulticastRouting() {}

        @RequiresSimulationThreadDevice
        public void testRequiresSimulationThreadDevice() {}
    }

    /** A test class with only class annotations. */
    @RequiresThreadFeature
    @RequiresIpv6MulticastRouting
    @RequiresSimulationThreadDevice
    private static final class TestClassAnnotations {
        public void testRequiresThreadFeature() {}

        public void testRequiresIpv6MulticastRouting() {}

        public void testRequiresSimulationThreadDevice() {}
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        mManager = new ThreadNetworkManager(mContext, mock(IThreadNetworkManager.class));
        mCheckerRule = new ThreadFeatureCheckerRule(mContext, "5.15.0-android14");
        mBaseStatement = new EmptyStatement();
    }

    @Test
    public void applyRequiresThreadFeature_methodAnnotatedAndThreadIsMissing_testSkipped()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresThreadFeature",
                        RequiresThreadFeature.class);
        when(mContext.getSystemService(ThreadNetworkManager.class)).thenReturn(null);

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertThrows(AssumptionViolatedException.class, () -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isFalse();
    }

    @Test
    public void applyRequiresThreadFeature_methodAnnotatedAndThreadIsPresent_testExecuted()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresThreadFeature",
                        RequiresThreadFeature.class);
        when(mContext.getSystemService(ThreadNetworkManager.class)).thenReturn(mManager);

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertDoesNotThrow(() -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isTrue();
    }

    @Test
    public void applyRequiresIpv6MulticastRouting_methodAnnotatedAndKernelVerIs5P14_testSkipped()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresIpv6MulticastRouting",
                        RequiresIpv6MulticastRouting.class);
        mCheckerRule = new ThreadFeatureCheckerRule(mContext, "5.14.0-android14");

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertThrows(AssumptionViolatedException.class, () -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isFalse();
    }

    @Test
    public void applyRequiresIpv6MulticastRouting_methodAnnotatedAndAndroidVerIs13_testSkipped()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresIpv6MulticastRouting",
                        RequiresIpv6MulticastRouting.class);
        mCheckerRule = new ThreadFeatureCheckerRule(mContext, "5.15.0-android13");

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertThrows(AssumptionViolatedException.class, () -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isFalse();
    }

    @Test
    public void applyRequiresIpv6MulticastRouting_methodAnnotatedAndVer5P15Android14_testExecuted()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresIpv6MulticastRouting",
                        RequiresIpv6MulticastRouting.class);
        mCheckerRule = new ThreadFeatureCheckerRule(mContext, "5.15.0-android14");

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertDoesNotThrow(() -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isTrue();
    }

    @Test
    public void applyRequiresSimulationThreadDevice_methodAnnotatedAndIsCuttlefish_testExecuted()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresSimulationThreadDevice",
                        RequiresSimulationThreadDevice.class);
        SystemProperties.set("ro.product.model", "Cuttlefish x86_64 phone");

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertDoesNotThrow(() -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isTrue();
    }

    @Test
    public void applyRequiresSimulationThreadDevice_methodAnnotatedAndIsNotCuttlefish_testSkipped()
            throws Exception {
        Description description =
                newDescription(
                        TestMethodAnnotations.class,
                        "testRequiresSimulationThreadDevice",
                        RequiresSimulationThreadDevice.class);
        SystemProperties.set("ro.product.model", "Pixel arm64 phone");

        Statement statement = mCheckerRule.apply(mBaseStatement, description);

        assertThrows(AssumptionViolatedException.class, () -> statement.evaluate());
        assertThat(mBaseStatement.isTestExecuted()).isFalse();
    }

    private static void assertDoesNotThrow(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            fail("Should not have thrown " + e);
        }
    }

    private static <TC, TA extends Annotation> Description newDescription(
            Class<TC> clazz, String methodName, Class<TA> annotationClass)
            throws NoSuchMethodException {
        Annotation annotation = clazz.getMethod(methodName).getAnnotation(annotationClass);
        assertThat(annotation).isNotNull();
        return Description.createTestDescription(clazz, methodName, annotation);
    }
}
