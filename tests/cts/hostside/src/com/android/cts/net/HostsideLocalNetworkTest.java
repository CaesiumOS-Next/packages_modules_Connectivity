package com.android.cts.net;

import android.compat.cts.CompatChangeGatingTestCase;
import org.junit.Test;

import com.google.common.collect.ImmutableSet;

public class HostsideLocalNetworkTest extends HostsideNetworkTestCase{//} CompatChangeGatingTestCase {

//    protected static final String TEST_PKG = "com.android.cts.net.hostside";
//    protected static final String TEST_APK = "CtsHostsideNetworkTestsApp.apk";

    private static final long RESTRICT_LOCAL_NETWORK_CHANGE_ID = 143937733;

//    @Override
//    protected void setUp() throws Exception {
//        installPackage(TEST_APK, true);
//    }

    @Test
    public void testLocalNetworkRestrictions() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "permissionChanged_checkLocalNetworkRestrictions");
//                /*enabledChanges*/ImmutableSet.of(RESTRICT_LOCAL_NETWORK_CHANGE_ID),
//                /*disabledChanges*/ ImmutableSet.of());
        // Any additional host side checks...
    }
}
