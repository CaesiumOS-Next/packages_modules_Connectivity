/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.net.module.util;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.provider.DeviceConfig.NAMESPACE_CAPTIVEPORTALLOGIN;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.net.module.util.FeatureVersions.CONNECTIVITY_MODULE_ID;
import static com.android.net.module.util.FeatureVersions.NETWORK_STACK_MODULE_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.Arrays;
import java.util.function.BiPredicate;


/**
 * Tests for DeviceConfigUtils.
 */
@SuppressWarnings("JUnitMalformedDeclaration") // Parameterized tests have arguments
@RunWith(JUnitParamsRunner.class)
@SmallTest
public class DeviceConfigUtilsTest {
    private static final String TEST_NAME_SPACE = "connectivity";
    private static final String TEST_EXPERIMENT_FLAG = "experiment_flag";
    private static final int TEST_FLAG_VALUE = 28;
    private static final String TEST_FLAG_VALUE_STRING = "28";
    private static final int TEST_DEFAULT_FLAG_VALUE = 0;
    private static final int TEST_MAX_FLAG_VALUE = 1000;
    private static final int TEST_MIN_FLAG_VALUE = 100;
    private static final long TEST_PACKAGE_VERSION = 290000000;
    // The APEX name is the name of the APEX module, as in android.content.pm.ModuleInfo, and is
    // used for its mount point in /apex. APEX packages are actually APKs with a different
    // file extension, so they have an AndroidManifest: the APEX package name is the package name in
    // that manifest, and is reflected in android.content.pm.ApplicationInfo. Contrary to the APEX
    // (module) name, different package names are typically used to identify the organization that
    // built and signed the APEX modules.
    private static final String TEST_APEX_PACKAGE_NAME = "com.prefix.android.tethering";
    private static final String TEST_GO_APEX_PACKAGE_NAME = "com.prefix.android.go.tethering";
    private static final String TEST_CONNRES_PACKAGE_NAME =
            "com.prefix.android.connectivity.resources";
    private static final String TEST_NETWORKSTACK_NAME = "com.prefix.android.networkstack";
    private static final String TEST_GO_NETWORKSTACK_NAME = "com.prefix.android.go.networkstack";
    private static final String TEST_CAPTIVEPORTALLOGIN_NAME =
            "com.prefix.android.captiveportallogin";
    private static final String TEST_SYSTEMSERVER_PACKAGE_NAME = "android";
    private static final String TEST_NAMING_TEMPLATE = "{method}_{params}";
    private final PackageInfo mPackageInfo = new PackageInfo();
    private final PackageInfo mApexPackageInfo = new PackageInfo();
    private MockitoSession mSession;

    @Mock private Context mContext;
    @Mock private PackageManager mPm;
    @Mock private Resources mResources;

    private enum Module {
        NETWORKSTACK(TEST_NETWORKSTACK_NAME, NAMESPACE_CONNECTIVITY,
                DeviceConfigUtils::isNetworkStackFeatureEnabled,
                DeviceConfigUtils::isNetworkStackFeatureNotChickenedOut),
        TETHERING(TEST_APEX_PACKAGE_NAME, NAMESPACE_TETHERING,
                DeviceConfigUtils::isTetheringFeatureEnabled,
                DeviceConfigUtils::isTetheringFeatureNotChickenedOut),
        CAPTIVEPORTALLOGIN(TEST_CAPTIVEPORTALLOGIN_NAME, NAMESPACE_CAPTIVEPORTALLOGIN,
                DeviceConfigUtils::isCaptivePortalLoginFeatureEnabled,
                (ctx, pkgName) -> {
                    throw new UnsupportedOperationException(
                        "isFeatureNotChickenedOutMethod not implemented for captiveportallogin");
                });

        public final String pkgName;
        public final String namespace;
        public final BiPredicate<Context, String> isFeatureEnabledMethod;
        public final BiPredicate<Context, String> isFeatureNotChickenedOutMethod;

        Module(String pkgName, String namespace,
                BiPredicate<Context, String> isFeatureEnabledMethod,
                BiPredicate<Context, String> isFeatureNotChickenedOutMethod) {
            this.pkgName = pkgName;
            this.namespace = namespace;
            this.isFeatureEnabledMethod = isFeatureEnabledMethod;
            this.isFeatureNotChickenedOutMethod = isFeatureNotChickenedOutMethod;
        }

        @Override
        public String toString() {
            return super.toString();
        }
    }

    /** Get test parameters for tests covering all modules */
    public Object moduleParameters() {
        return Module.values();
    }

    /** Get test parameters for isFeatureNotChickenedOut tests */
    public Object moduleSupportingFeatureNotChickenedOutParameters() {
        return new Module[] { Module.NETWORKSTACK, Module.TETHERING };
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSession = mockitoSession().spyStatic(DeviceConfig.class).startMocking();

        mPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION);
        mApexPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION);

        doReturn(mPm).when(mContext).getPackageManager();
        doThrow(NameNotFoundException.class).when(mPm).getPackageInfo(anyString(), anyInt());
        doReturn(mApexPackageInfo).when(mPm).getPackageInfo(eq(TEST_APEX_PACKAGE_NAME), anyInt());
        doReturn(mResources).when(mContext).getResources();

        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.applicationInfo = new ApplicationInfo();
        ri.activityInfo.applicationInfo.packageName = TEST_CONNRES_PACKAGE_NAME;
        ri.activityInfo.applicationInfo.sourceDir =
                "/apex/com.android.tethering/priv-app/ServiceConnectivityResources@version";
        doReturn(Arrays.asList(ri)).when(mPm).queryIntentActivities(argThat(
                intent -> intent.getAction().equals(DeviceConfigUtils.RESOURCES_APK_INTENT)),
                eq(MATCH_SYSTEM_ONLY));
    }

    private void setupMockPackage(Module module) throws NameNotFoundException {
        doReturn(module.pkgName).when(mContext).getPackageName();
        doReturn(mPackageInfo).when(mPm).getPackageInfo(eq(module.pkgName), anyInt());
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
        DeviceConfigUtils.resetPackageVersionCacheForTest();
    }

    @Test
    public void testGetDeviceConfigPropertyInt_Null() {
        doReturn(null).when(() -> DeviceConfig.getProperty(eq(TEST_NAME_SPACE),
                eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_DEFAULT_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_NotNull() {
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(eq(TEST_NAME_SPACE),
                eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_NormalValue() {
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(eq(TEST_NAME_SPACE),
                eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, 0 /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_NullValue() {
        doReturn(null).when(() -> DeviceConfig.getProperty(
                eq(TEST_NAME_SPACE), eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_DEFAULT_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, 0 /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_OverMaximumValue() {
        doReturn(Integer.toString(TEST_MAX_FLAG_VALUE + 10)).when(() -> DeviceConfig.getProperty(
                eq(TEST_NAME_SPACE), eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_DEFAULT_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, TEST_MIN_FLAG_VALUE /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_EqualsMaximumValue() {
        doReturn(Integer.toString(TEST_MAX_FLAG_VALUE)).when(() -> DeviceConfig.getProperty(
                eq(TEST_NAME_SPACE), eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_MAX_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, TEST_MIN_FLAG_VALUE /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_BelowMinimumValue() {
        doReturn(Integer.toString(TEST_MIN_FLAG_VALUE - 10)).when(() -> DeviceConfig.getProperty(
                eq(TEST_NAME_SPACE), eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_DEFAULT_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, TEST_MIN_FLAG_VALUE /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyInt_EqualsMinimumValue() {
        doReturn(Integer.toString(TEST_MIN_FLAG_VALUE)).when(() -> DeviceConfig.getProperty(
                eq(TEST_NAME_SPACE), eq(TEST_EXPERIMENT_FLAG)));
        assertEquals(TEST_MIN_FLAG_VALUE, DeviceConfigUtils.getDeviceConfigPropertyInt(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG, TEST_MIN_FLAG_VALUE /* minimum value */,
                TEST_MAX_FLAG_VALUE /* maximum value */,
                TEST_DEFAULT_FLAG_VALUE /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyBoolean_Null() {
        doReturn(null).when(() -> DeviceConfig.getProperty(eq(TEST_NAME_SPACE),
                eq(TEST_EXPERIMENT_FLAG)));
        assertFalse(DeviceConfigUtils.getDeviceConfigPropertyBoolean(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG,
                false /* default value */));
    }

    @Test
    public void testGetDeviceConfigPropertyBoolean_NotNull() {
        doReturn("true").when(() -> DeviceConfig.getProperty(eq(TEST_NAME_SPACE),
                eq(TEST_EXPERIMENT_FLAG)));
        assertTrue(DeviceConfigUtils.getDeviceConfigPropertyBoolean(
                TEST_NAME_SPACE, TEST_EXPERIMENT_FLAG,
                false /* default value */));
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureEnabled(Module module) throws Exception {
        setupMockPackage(module);
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureEnabledFeatureDefaultDisabled(Module module) throws Exception {
        setupMockPackage(module);
        doReturn(null).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertFalse(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the flag is unset, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureEnabledFeatureForceEnabled(Module module) throws Exception {
        setupMockPackage(module);
        doReturn("1").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the feature is force enabled, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureEnabledFeatureForceDisabled(Module module)
            throws Exception {
        setupMockPackage(module);
        doReturn("-1").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertFalse(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the feature is force disabled, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testFeatureIsEnabledWithException(Module module) throws Exception {
        setupMockPackage(module);

        // Feature should be enabled by flag value "1".
        doReturn("1").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // Feature should be disabled by flag value "999999999".
        doReturn("999999999").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertFalse(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the flag is not set feature is disabled
        doReturn(null).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertFalse(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testFeatureIsEnabledOnGo(Module module) throws Exception {
        setupMockPackage(module);
        doReturn("0").when(() -> DeviceConfig.getProperty(
                module.namespace, TEST_EXPERIMENT_FLAG));

        assertFalse(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
    }

    @Test
    @Parameters(method = "moduleParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureEnabledCaching(Module module) throws Exception {
        setupMockPackage(module);
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));

        final InOrder inOrder = inOrder(mContext, mPm);
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
        inOrder.verify(mContext, atLeastOnce()).getPackageManager();
        inOrder.verify(mPm).getPackageInfo(anyString(), anyInt());

        // Package info is only queried once
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
        inOrder.verify(mContext, never()).getPackageManager();
        inOrder.verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void testIsNetworkStackFeatureEnabledCalledFromTethering() throws Exception {
        doReturn(TEST_SYSTEMSERVER_PACKAGE_NAME).when(mContext).getPackageName();
        doReturn(mPackageInfo).when(mPm).getPackageInfo(eq(TEST_NETWORKSTACK_NAME), anyInt());
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(
                NAMESPACE_CONNECTIVITY, TEST_EXPERIMENT_FLAG));

        assertTrue(DeviceConfigUtils.isNetworkStackFeatureEnabled(mContext, TEST_EXPERIMENT_FLAG));
    }

    @Test
    public void testGetResBooleanConfig() {
        final int someResId = 1234;
        doReturn(true).when(mResources).getBoolean(someResId);
        assertTrue(DeviceConfigUtils.getResBooleanConfig(mContext, someResId, false));
        doReturn(false).when(mResources).getBoolean(someResId);
        assertFalse(DeviceConfigUtils.getResBooleanConfig(mContext, someResId, false));
        doThrow(new Resources.NotFoundException()).when(mResources).getBoolean(someResId);
        assertFalse(DeviceConfigUtils.getResBooleanConfig(mContext, someResId, false));
    }

    @Test
    public void testGetResIntegerConfig() {
        final int someResId = 1234;
        doReturn(2097).when(mResources).getInteger(someResId);
        assertEquals(2097, DeviceConfigUtils.getResIntegerConfig(mContext, someResId, 2098));
        doThrow(new Resources.NotFoundException()).when(mResources).getInteger(someResId);
        assertEquals(2098, DeviceConfigUtils.getResIntegerConfig(mContext, someResId, 2098));
    }

    @Test
    public void testGetNetworkStackModuleVersionCaching() throws Exception {
        setupMockPackage(Module.NETWORKSTACK);
        final PackageInfo networkStackPackageInfo = new PackageInfo();
        networkStackPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION);
        doReturn(networkStackPackageInfo).when(mPm).getPackageInfo(
                eq(TEST_NETWORKSTACK_NAME), anyInt());
        assertEquals(TEST_PACKAGE_VERSION,
                DeviceConfigUtils.getNetworkStackModuleVersion(mContext));

        assertEquals(TEST_PACKAGE_VERSION,
                DeviceConfigUtils.getNetworkStackModuleVersion(mContext));
        // Package info is only queried once
        verify(mPm, times(1)).getPackageInfo(anyString(), anyInt());
    }

    @Test
    public void testGetNetworkStackModuleVersionOnNonMainline() {
        assertEquals(DeviceConfigUtils.DEFAULT_PACKAGE_VERSION,
                DeviceConfigUtils.getNetworkStackModuleVersion(mContext));
    }

    @Test
    public void testGetNetworkStackModuleVersion() throws Exception {
        final PackageInfo networkStackPackageInfo = new PackageInfo();
        final PackageInfo goNetworkStackPackageInfo = new PackageInfo();
        networkStackPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION);
        goNetworkStackPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION + 1);
        doReturn(goNetworkStackPackageInfo).when(mPm).getPackageInfo(
                eq(TEST_NETWORKSTACK_NAME), anyInt());
        // Verify the returned value is go module version.
        assertEquals(TEST_PACKAGE_VERSION + 1,
                DeviceConfigUtils.getNetworkStackModuleVersion(mContext));
    }

    @Test
    public void testIsFeatureSupported_networkStackFeature() throws Exception {
        // Supported for DEFAULT_PACKAGE_VERSION
        assertTrue(DeviceConfigUtils.isFeatureSupported(
                mContext, TEST_PACKAGE_VERSION + NETWORK_STACK_MODULE_ID));

        final PackageInfo networkStackPackageInfo = new PackageInfo();
        networkStackPackageInfo.setLongVersionCode(TEST_PACKAGE_VERSION);
        doReturn(networkStackPackageInfo).when(mPm).getPackageInfo(
                eq(TEST_NETWORKSTACK_NAME), anyInt());

        assertTrue(DeviceConfigUtils.isFeatureSupported(
                mContext, TEST_PACKAGE_VERSION + NETWORK_STACK_MODULE_ID));
        assertFalse(DeviceConfigUtils.isFeatureSupported(
                mContext, TEST_PACKAGE_VERSION + NETWORK_STACK_MODULE_ID + 1));
    }

    @Test
    public void testIsFeatureSupported_tetheringFeature() throws Exception {
        assertTrue(DeviceConfigUtils.isFeatureSupported(
                mContext, TEST_PACKAGE_VERSION + CONNECTIVITY_MODULE_ID));
        // Return false because feature requires a future version.
        assertFalse(DeviceConfigUtils.isFeatureSupported(
                mContext, 889900000L + CONNECTIVITY_MODULE_ID));
    }

    @Test
    public void testIsFeatureSupported_illegalModule() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> DeviceConfigUtils.isFeatureSupported(mContext, TEST_PACKAGE_VERSION));
    }

    @Test
    @Parameters(method = "moduleSupportingFeatureNotChickenedOutParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureNotChickenedOut(Module module) throws Exception {
        setupMockPackage(module);
        doReturn(TEST_FLAG_VALUE_STRING).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureEnabledMethod.test(mContext, TEST_EXPERIMENT_FLAG));
    }

    @Test
    @Parameters(method = "moduleSupportingFeatureNotChickenedOutParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureNotChickenedOutFeatureDefaultEnabled(Module module) throws Exception {
        doReturn(null).when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureNotChickenedOutMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the flag is unset, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    @Parameters(method = "moduleSupportingFeatureNotChickenedOutParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureNotChickenedOutFeatureForceEnabled(Module module) throws Exception {
        doReturn("1").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertTrue(module.isFeatureNotChickenedOutMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the feature is force enabled, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }

    @Test
    @Parameters(method = "moduleSupportingFeatureNotChickenedOutParameters")
    @TestCaseName(TEST_NAMING_TEMPLATE)
    public void testIsFeatureNotChickenedOutFeatureForceDisabled(Module module) throws Exception {
        doReturn("-1").when(() -> DeviceConfig.getProperty(module.namespace,
                TEST_EXPERIMENT_FLAG));
        assertFalse(module.isFeatureNotChickenedOutMethod.test(mContext, TEST_EXPERIMENT_FLAG));

        // If the feature is force disabled, package info is not queried
        verify(mContext, never()).getPackageManager();
        verify(mPm, never()).getPackageInfo(anyString(), anyInt());
    }
}
