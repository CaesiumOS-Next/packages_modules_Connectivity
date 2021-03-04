/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Build;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.apishim.VpnServiceBuilderShimImpl;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Test;
import org.junit.runner.RunWith;

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

    private void excludeRoute(InetAddress address, int prefixLength)
            throws UnsupportedApiLevelException {
        VpnServiceBuilderShimImpl.newInstance().excludeRoute(mBuilder,
                new IpPrefix(address, prefixLength));
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testExcludedRoute_IPv4() throws UnsupportedApiLevelException {
        testExcludedRoute(ADDRESS_V4, PREFIX_V4);
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testExcludedRoute_IPv6() throws UnsupportedApiLevelException {
        testExcludedRoute(ADDRESS_V6, PREFIX_V6);
    }

    private void testExcludedRoute(InetAddress address, int prefixLength)
            throws UnsupportedApiLevelException {
        excludeRoute(address, prefixLength);

        assertEquals(1, routes().size());
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
        assertEquals(RouteInfo.RTN_UNICAST, routes().get(0).getType());
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testDuplicatedRoute_IPv4() throws UnsupportedApiLevelException {
        testDuplicatedRoute(ADDRESS_V4, PREFIX_V4, ADDRESS2_V4, ADDRESS3_V4);
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testDuplicatedRoute_IPv6() throws UnsupportedApiLevelException {
        testDuplicatedRoute(ADDRESS_V6, PREFIX_V6, ADDRESS2_V6, ADDRESS3_V6);
    }

    private void testDuplicatedRoute(InetAddress address, int prefixLength,
            InetAddress... additionalAddresses) throws UnsupportedApiLevelException {
        final int totalRoutesCount = additionalAddresses.length + 1;
        final int lastRouteIndex = additionalAddresses.length;

        for (InetAddress addr: additionalAddresses) {
            addRoute(addr, prefixLength);
        }

        excludeRoute(address, prefixLength);

        assertEquals(totalRoutesCount, routes().size());
        assertEquals(RouteInfo.RTN_THROW, routes().get(lastRouteIndex).getType());

        addRoute(address, prefixLength);

        assertEquals(totalRoutesCount, routes().size());
        assertEquals(RouteInfo.RTN_UNICAST, routes().get(lastRouteIndex).getType());

        excludeRoute(address, prefixLength);

        assertEquals(totalRoutesCount, routes().size());
        assertEquals(RouteInfo.RTN_THROW, routes().get(lastRouteIndex).getType());
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testDisjointRoutes_IPv4() throws UnsupportedApiLevelException {
        testDisjointRoutes(ADDRESS_V4, ADDRESS2_V4, PREFIX_V4);
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testDisjointRoutes_IPv6() throws UnsupportedApiLevelException {
        testDisjointRoutes(ADDRESS_V6, ADDRESS2_V6, PREFIX_V6);
    }

    private void testDisjointRoutes(InetAddress unicastAddress, InetAddress throwAddress,
            int prefixLength) throws UnsupportedApiLevelException {
        addRoute(unicastAddress, prefixLength);
        excludeRoute(throwAddress, prefixLength);

        assertEquals(2, routes().size());
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testInterleavedRoutes_IPv4() throws UnsupportedApiLevelException {
        testInterleavedRoutes(ADDRESS_V4, PREFIX_V4_SHORT, PREFIX_V4, PREFIX_V4_LONG);
    }

    @IgnoreUpTo(Build.VERSION_CODES.S)
    @Test
    public void testInterleavedRoutes_IPv6() throws UnsupportedApiLevelException {
        testInterleavedRoutes(ADDRESS_V6, PREFIX_V6_SHORT, PREFIX_V6, PREFIX_V6_LONG);
    }

    private void testInterleavedRoutes(InetAddress address, int... prefixLengths)
            throws UnsupportedApiLevelException {
        for (int i = 0; i < prefixLengths.length; i++) {
            if (i % 2 == 0) {
                addRoute(address, prefixLengths[i]);
            } else {
                excludeRoute(address, prefixLengths[i]);
            }
        }

        assertEquals(prefixLengths.length, routes().size());
    }

    private List<RouteInfo> routes() {
        return mBuilder.routes();
    }
}
