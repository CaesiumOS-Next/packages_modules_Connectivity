/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.networkstack.apishim.VpnServiceBuilderShimImpl;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VpnServiceBuilderTest {
    private static final InetAddress ADDRESS_V4 = address("192.0.0.0");
    private static final InetAddress ADDRESS2_V4 = address("193.0.0.0");
    private static final InetAddress ADDRESS3_V4 = address("194.0.0.0");
    private static final InetAddress ADDRESS_V6 = address("2001:db8::");
    private static final InetAddress ADDRESS2_V6 = address("2001:db9::");
    private static final InetAddress ADDRESS3_V6 = address("2001:dba::");

    private static final int PREFIX_V4_SHORT = 8;
    private static final int PREFIX_V4 = 16;
    private static final int PREFIX_V4_LONG = 32;

    private static final int PREFIX_V6_SHORT = 32;
    private static final int PREFIX_V6 = 64;
    private static final int PREFIX_V6_LONG = 128;

    private static InetAddress address(String address) {
        return InetAddresses.parseNumericAddress(address);
    }

    private final VpnService.Builder mBuilder = new VpnService().new Builder();

    private void addRoute(InetAddress address, int prefixLength) {
        mBuilder.addRoute(address, prefixLength);
    }

    private void excludeRoute(InetAddress address, int prefixLength) {
        try {
            VpnServiceBuilderShimImpl.newInstance().excludeRoute(mBuilder,
                    new IpPrefix(address, prefixLength));
        } catch (UnsupportedApiLevelException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testExcludedRoute_IPv4() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testExcludedRoute(ADDRESS_V4, PREFIX_V4);
    }

    @Test
    public void testExcludedRoute_IPv6() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testExcludedRoute(ADDRESS_V6, PREFIX_V6);
    }

    private void testExcludedRoute(InetAddress address, int prefixLength) {
        excludeRoute(address, prefixLength);

        assertEquals(1, routes().size());
        assertNoIpFamilyAllowed();
        assertEquals(RouteInfo.RTN_THROW, routes().get(0).getType());
    }

    @Test
    public void testIncludedRoute_IPv4() {
        testIncludedRoute(ADDRESS_V4, PREFIX_V4);
    }

    @Test
    public void testIncludedRoute_IPv6() {
        testIncludedRoute(ADDRESS_V6, PREFIX_V6);
    }

    private void testIncludedRoute(InetAddress address, int prefixLength) {
        addRoute(address, prefixLength);

        assertEquals(1, routes().size());
        assertIpFamilyAllowedByAddress(address);
        assertEquals(RouteInfo.RTN_UNICAST, routes().get(0).getType());
    }

    @Test
    public void testDuplicatedRoute_IPv4() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testDuplicatedRoute(ADDRESS_V4, PREFIX_V4, ADDRESS2_V4, ADDRESS3_V4);
    }

    @Test
    public void testDuplicatedRoute_IPv6() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testDuplicatedRoute(ADDRESS_V6, PREFIX_V6, ADDRESS2_V6, ADDRESS3_V6);
    }

    private void testDuplicatedRoute(InetAddress address, int prefixLength,
            InetAddress... additionalAddresses) {
        final int additionalRoutesCount = additionalAddresses.length;

        for (InetAddress addr: additionalAddresses) {
            addRoute(addr, prefixLength);
        }

        excludeRoute(address, prefixLength);

        assertEquals(additionalRoutesCount + 1, routes().size());
        assertEquals(RouteInfo.RTN_THROW, routes().get(additionalRoutesCount).getType());

        addRoute(address, prefixLength);

        assertEquals(additionalRoutesCount + 1, routes().size());
        assertIpFamilyAllowedByAddress(address);
        assertEquals(RouteInfo.RTN_UNICAST, routes().get(additionalRoutesCount).getType());

        excludeRoute(address, prefixLength);

        assertEquals(additionalRoutesCount + 1, routes().size());
        assertIpFamilyAllowedByAddress(address);
        assertEquals(RouteInfo.RTN_THROW, routes().get(additionalRoutesCount).getType());
    }

    @Test
    public void testDisjointedRoutes_IPv4() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testDisjointedRoutes(ADDRESS_V4, ADDRESS2_V4, PREFIX_V4);
    }

    @Test
    public void testDisjointedRoutes_IPv6() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testDisjointedRoutes(ADDRESS_V6, ADDRESS2_V6, PREFIX_V6);
    }

    private void testDisjointedRoutes(InetAddress unicastAddress, InetAddress throwAddress,
            int prefixLength) {
        addRoute(unicastAddress, prefixLength);
        excludeRoute(throwAddress, prefixLength);

        assertEquals(2, routes().size());
        assertIpFamilyAllowedByAddress(unicastAddress);
    }

    @Test
    public void testInterleavedRoutes_IPv4() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testInterleavedRoutes(ADDRESS_V4, PREFIX_V4_SHORT, PREFIX_V4, PREFIX_V4_LONG);
    }

    @Test
    public void testInterleavedRoutes_IPv6() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S) when finalized
        assumeTrue(SdkLevel.isAtLeastT());

        testInterleavedRoutes(ADDRESS_V6, PREFIX_V6_SHORT, PREFIX_V6, PREFIX_V6_LONG);
    }

    private void testInterleavedRoutes(InetAddress address, int... prefixLengths) {
        for (int i = 0; i < prefixLengths.length; i++) {
            if (i % 2 == 0) {
                addRoute(address, prefixLengths[i]);
            } else {
                excludeRoute(address, prefixLengths[i]);
            }
        }

        assertEquals(prefixLengths.length, routes().size());
        assertIpFamilyAllowedByAddress(address);
    }

    private List<RouteInfo> routes() {
        return mBuilder.routes();
    }

    private void assertIpFamilyAllowedByAddress(InetAddress address) {
        assertEquals(address instanceof Inet4Address, mBuilder.configuration().allowIPv4);
        assertEquals(address instanceof Inet6Address, mBuilder.configuration().allowIPv6);
    }

    private void assertNoIpFamilyAllowed() {
        assertFalse(mBuilder.configuration().allowIPv4);
        assertFalse(mBuilder.configuration().allowIPv6);
    }
}
