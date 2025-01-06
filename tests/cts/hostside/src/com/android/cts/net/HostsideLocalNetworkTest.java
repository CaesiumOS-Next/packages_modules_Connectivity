package com.android.cts.net;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.host.HostFlagsValueProvider;

import org.junit.Rule;
import org.junit.Test;

public class HostsideLocalNetworkTest extends HostsideNetworkTestCase{

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            HostFlagsValueProvider.createCheckFlagsRule(this::getDevice);

    @Test
    @RequiresFlagsEnabled("com.android.net.flags.restrict_local_network")
    public void testLocalNetworkRestrictions() throws Exception {
        // Test apps are set to targetSDK = CUR_DEVELOPMENT so app compat changes are automatically
        // picked up
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "permissionChanged_checkLocalNetworkRestrictions");
    }
}
