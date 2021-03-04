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
import static org.junit.Assume.assumeTrue;

import com.android.modules.utils.build.SdkLevel;
import com.android.networkstack.apishim.VpnServiceBuilderShimImpl;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class VpnServiceBuilderTest {
    public boolean mIsIPv4Route;
    public boolean mIsIPv6Route;

    @Parameterized.Parameter
    public String mRouteAddress;

    @Parameterized.Parameter(1)
    public int mRoutePrefixLength;

    @Parameterized.Parameters
    public static Collection<Object[]> testData() {
        return Arrays.asList(new Object[][]{
                {"192.0.2.0" /* routeAddress */, 24 /* routePrefixLength */},
                {"2001:db8::", 32},
        });
    }

    private VpnService.Builder mBuilder = new VpnService().new Builder();
    private List<RouteInfo> mRoutes;

    private void addRoute() {
        mBuilder.addRoute(mRouteAddress, mRoutePrefixLength);
    }

    private void excludeRoute() {
        try {
            VpnServiceBuilderShimImpl.newInstance().excludeRoute(mBuilder,
                    InetAddresses.parseNumericAddress(mRouteAddress), mRoutePrefixLength);
        } catch (UnsupportedApiLevelException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setUp() {
        mBuilder = new VpnService().new Builder();
        mRoutes = mBuilder.routes();
        InetAddress routeInetAddress = InetAddresses.parseNumericAddress(mRouteAddress);
        mIsIPv4Route = routeInetAddress instanceof Inet4Address;
        mIsIPv6Route = routeInetAddress instanceof Inet6Address;
    }

    @Test
    public void testExcludedRoute() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S)
        assumeTrue(SdkLevel.isAtLeastT());

        excludeRoute();

        assertEquals(1, mRoutes.size());
        assertIpFamilyAllowed(false /* allowIPv4 */, false /* allowIPv6 */);
        assertEquals(RouteInfo.RTN_THROW, mRoutes.get(0).getType());
    }

    @Test
    public void testIncludedRoute() {
        addRoute();

        assertEquals(1, mRoutes.size());
        assertIpFamilyAllowed(mIsIPv4Route, mIsIPv6Route);
        assertEquals(RouteInfo.RTN_UNICAST, mRoutes.get(0).getType());
    }

    @Test
    public void testDuplicatedRoute() {
        // TODO: replace with @IgnoreUpTo(Build.VERSION_CODES.S)
        assumeTrue(SdkLevel.isAtLeastT());

        excludeRoute();
        addRoute();

        assertEquals(1, mRoutes.size());
        assertIpFamilyAllowed(mIsIPv4Route, mIsIPv6Route);
        assertEquals(RouteInfo.RTN_UNICAST, mRoutes.get(0).getType());

        excludeRoute();

        assertEquals(1, mRoutes.size());
        assertIpFamilyAllowed(mIsIPv4Route, mIsIPv6Route);
        assertEquals(RouteInfo.RTN_THROW, mRoutes.get(0).getType());
    }

    private void assertIpFamilyAllowed(boolean allowIPv4, boolean allowIPv6) {
        assertEquals(allowIPv4, mBuilder.configuration().allowIPv4);
        assertEquals(allowIPv6, mBuilder.configuration().allowIPv6);
    }
}
