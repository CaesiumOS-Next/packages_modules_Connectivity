package com.android.cts.net;

import android.compat.cts.CompatChangeGatingTestCase;

import com.google.common.collect.ImmutableSet;

public class HostsideLocalNetworkTest extends CompatChangeGatingTestCase {

    protected static final String TEST_PKG = "com.android.cts.net.hostside";
    protected static final String TEST_APK = "CtsHostsideNetworkTestsApp.apk";

    private static final long RESTRICT_LOCAL_NETWORK_CHANGE_ID = 365139289;

    @Override
    protected void setUp() throws Exception {
        installPackage(TEST_APK, true);
    }

    public void testLocalNetworkRestrictions() throws Exception {
        runDeviceCompatTest(TEST_PKG, ".LocalNetworkTest",
                "permissionChanged_checkLocalNetworkRestrictions",
                /*enabledChanges*/ImmutableSet.of(RESTRICT_LOCAL_NETWORK_CHANGE_ID),
                /*disabledChanges*/ ImmutableSet.of());
        // Any additional host side checks...
    }
}
