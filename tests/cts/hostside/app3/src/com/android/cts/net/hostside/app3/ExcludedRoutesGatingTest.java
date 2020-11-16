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

package com.android.cts.net.hostside.app3;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.RouteInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExcludedRoutesGatingTest {
    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testExcludedRoutesEnabled() {
        LinkProperties lp = makeLinkPropertiesWithExcludedRoutes();

        assertEquals(2, lp.getRoutes().size());
        assertEquals(2, lp.getAllRoutes().size());
    }

    @Test
    public void testExcludedRoutesDisabled() {
        LinkProperties lp = makeLinkPropertiesWithExcludedRoutes();

        assertEquals(0, lp.getRoutes().size());
        assertEquals(0, lp.getAllRoutes().size());
    }

    private LinkProperties makeLinkPropertiesWithExcludedRoutes() {
        LinkProperties lp = new LinkProperties();

        lp.addRoute(new RouteInfo(new IpPrefix("10.0.0.0/8"), RouteInfo.RTN_THROW));
        lp.addRoute(new RouteInfo(new IpPrefix("2001:db8::/64"), RouteInfo.RTN_UNREACHABLE));

        return lp;
    }
}
