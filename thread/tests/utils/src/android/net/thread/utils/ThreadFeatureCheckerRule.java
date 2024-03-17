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

import static com.android.testutils.DeviceInfoUtils.isKernelVersionAtLeast;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.thread.ThreadNetworkManager;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.annotations.VisibleForTesting;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule used to skip Thread tests when the device doesn't support a specific feature indicated by
 * {@code ThreadFeatureCheckerRule.Requires*}.
 */
public final class ThreadFeatureCheckerRule implements TestRule {
    private static final String KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED = "5.15.0";
    private static final int KERNEL_ANDROID_VERSION_MULTICAST_ROUTING_SUPPORTED = 14;

    private final Context mContext;
    private final String mOsRelease;
    private final String mModelName;

    /**
     * Annotates a test class or method requires the Thread feature to run.
     *
     * <p>In Absence of the Thread feature, the test class or method will be ignored.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface RequiresThreadFeature {}

    /**
     * Annotates a test class or method requires the kernel IPv6 multicast routing feature to run.
     *
     * <p>In Absence of the multicast routing feature, the test class or method will be ignored.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface RequiresIpv6MulticastRouting {}

    /**
     * Annotates a test class or method requires the simulation Thread device (i.e. ot-cli-ftd) to
     * run.
     *
     * <p>In Absence of the simulation device, the test class or method will be ignored.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface RequiresSimulationThreadDevice {}

    public ThreadFeatureCheckerRule() {
        this(
                ApplicationProvider.getApplicationContext(),
                VintfRuntimeInfo.getOsRelease(),
                SystemProperties.get("ro.product.model"));
    }

    @VisibleForTesting
    ThreadFeatureCheckerRule(Context context, String osRelease, String modelName) {
        mContext = context;
        mOsRelease = osRelease;
        mModelName = modelName;
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (hasAnnotation(RequiresThreadFeature.class, description)) {
                    assumeTrue(
                            "Skipping test because the Thread feature is unavailable",
                            hasThreadFeature());
                }

                if (hasAnnotation(RequiresIpv6MulticastRouting.class, description)) {
                    assumeTrue(
                            "Skipping test because kernel IPv6 multicast routing is unavailable",
                            hasIpv6MulticastRouting());
                }

                if (hasAnnotation(RequiresSimulationThreadDevice.class, description)) {
                    assumeTrue(
                            "Skipping test because simulation Thread device is unavailable",
                            hasSimulationThreadDevice());
                }

                base.evaluate();
            }
        };
    }

    /** Returns {@code true} if a test method or the test class is annotated with annotation. */
    private <T extends Annotation> boolean hasAnnotation(
            Class<T> annotationClass, Description description) {
        // Method annotation
        boolean hasAnnotation = description.getAnnotation(annotationClass) != null;

        // Class annotation
        Class<?> clazz = description.getTestClass();
        while (!hasAnnotation && clazz != Object.class) {
            hasAnnotation |= clazz.getAnnotation(annotationClass) != null;
            clazz = clazz.getSuperclass();
        }

        return hasAnnotation;
    }

    /** Returns {@code true} if this device has the Thread feature supported. */
    private boolean hasThreadFeature() {
        return mContext.getSystemService(ThreadNetworkManager.class) != null;
    }

    /**
     * Returns {@code true} if this device has the kernel IPv6 multicast routing feature enabled.
     */
    private boolean hasIpv6MulticastRouting() {
        String kernelVersion = getKernelVersion(mOsRelease);
        int androidVersion = getKernelAndroidVersion(mOsRelease);

        // The kernel IPv6 multicast routing (i.e. IPV6_MROUTE) is enabled on kernel version
        // 5.15.0-android14 and later
        return isKernelVersionAtLeast(kernelVersion, KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED)
                && androidVersion >= KERNEL_ANDROID_VERSION_MULTICAST_ROUTING_SUPPORTED;
    }

    /**
     * Parses the kernel version from the OS release version.
     *
     * <p>For example, the kernel version is "6.1.68" when {@code osRelease} is
     * "6.1.68-android14-11-ga7f647f49daf-ab11484633".
     *
     * @throws IllegalArgumentException if {@code osRelease} doesn't start with the kernel version
     */
    private static final String getKernelVersion(String osRelease) {
        final Pattern pattern = Pattern.compile("^\\d+\\.\\d+\\.\\d+");
        Matcher matcher = pattern.matcher(osRelease);
        if (!matcher.find()) {
            throw new IllegalArgumentException(osRelease + " doesn't start with kernel version");
        }
        return matcher.group(0);
    }

    /**
     * Parses the kernel android version from the OS release version.s
     *
     * <p>For example, the androids version is {@code 14} when {@code osRelease} is
     * "6.1.68-android14-11-ga7f647f49daf-ab11484633".
     *
     * @throws IllegalArgumentException if {@code osRelease} doesn't contains the kernel android
     *     version
     */
    private static int getKernelAndroidVersion(String osRelease) {
        final Pattern pattern = Pattern.compile("android(\\d+)");
        Matcher matcher = pattern.matcher(osRelease);
        if (!matcher.find()) {
            throw new IllegalArgumentException(osRelease + " doesn't contain android version");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** Returns {@code true} if the simulation Thread device is supported. */
    private boolean hasSimulationThreadDevice() {
        // Simulation radio is supported on only Cuttlefish
        return mModelName.startsWith("Cuttlefish");
    }
}
