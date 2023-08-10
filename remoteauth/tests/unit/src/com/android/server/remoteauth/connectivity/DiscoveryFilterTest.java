/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.remoteauth.connectivity;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.server.remoteauth.connectivity.DiscoveryFilter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DiscoveryFilter}. */
@RunWith(AndroidJUnit4.class)
public class DiscoveryFilterTest {
    private static final String DEVICE_NAME = "device name";
    private static final String PEER_ADDRESS = "peer address";

    @Before
    public void setUp() {}

    @Test
    public void testBuildDiscoveryFilter_succeeds() {
        DiscoveryFilter filter =
                DiscoveryFilter.Builder.newInstance()
                        .setDeviceName(DEVICE_NAME)
                        .setDeviceType(DiscoveryFilter.DeviceType.WATCH)
                        .setPeerAddress(PEER_ADDRESS)
                        .build();

        assertEquals(filter.getDeviceType(), DiscoveryFilter.DeviceType.WATCH);
        assertEquals(filter.getDeviceName(), DEVICE_NAME);
        assertEquals(filter.getPeerAddress(), PEER_ADDRESS);
    }
}
